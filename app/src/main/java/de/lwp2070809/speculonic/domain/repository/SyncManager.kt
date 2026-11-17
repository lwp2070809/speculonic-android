package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.ensureOk
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.ResponseBody

enum class SyncOutcome {
    SYNCED_FULL,
    SKIPPED_NOT_CONFIGURED,
    SKIPPED_LOCK,
    SKIPPED_RATE_LIMIT,
    SKIPPED_UP_TO_DATE
}

private data class EntityCountSnapshot(
    val artists: Int,
    val albums: Int,
    val songs: Int
)

private data class Search3LoopOutcome(
    val offsetBugDetected: Boolean
)

private data class PaginationStep(
    val hasMore: Boolean,
    val nextOffset: Int,
    val offsetBugDetected: Boolean
)

@OptIn(ExperimentalSerializationApi::class)
class SyncManager(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val pref: PreferencesManager,
    private val entityMapper: EntityMapper,
    private val onSongsRemoved: suspend (List<SongCleanupTarget>) -> Unit = {},
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun syncAllData(
        serverCapabilities: ServerCapabilities,
        forceRefresh: Boolean = false,
        ignoreLastModified: Boolean = false,
        ignoreSafetyGuard: Boolean = false,
        hasLocalData: Boolean,
        keepSyncingState: Boolean = false,
        onProgress: (suspend (String) -> Unit)? = null,
        onSyncComplete: suspend (Long, Long) -> Unit
    ): SyncOutcome {
        if (!syncMutex.tryLock()) {
            LogManager.i("SyncManager: sync is already running, skipping duplicate request.")
            return SyncOutcome.SKIPPED_LOCK
        }
        try {
            val lastSync = pref.lastSyncTime.first()
            val lastServerModified = pref.serverLastModified.first()
            val currentTime = System.currentTimeMillis()

            if (!forceRefresh && (currentTime - lastSync < SYNC_DEBOUNCE_MILLIS) && hasLocalData) {
                LogManager.d("SyncManager: syncAllData skipped due to rate limiting")
                return SyncOutcome.SKIPPED_RATE_LIMIT
            }

            pref.saveIsSyncing(true)
            pref.saveSyncProgress(null)
            pref.saveSyncError(null)

            try {
                val (u, t, s) = authManager.getAuthParams()
                val progressMsg = "正在检查服务器更新..."
                onProgress?.invoke(progressMsg)
                pref.saveSyncProgress(progressMsg)

                val serverLastModified = fetchServerLastModified(
                    u = u,
                    t = t,
                    s = s,
                    ignoreLastModified = ignoreLastModified,
                    hasLocalData = hasLocalData,
                    lastServerModified = lastServerModified
                )

                val skipUnchanged = !ignoreLastModified &&
                    serverLastModified != 0L &&
                    serverLastModified == lastServerModified &&
                    hasLocalData
                if (skipUnchanged) {
                    LogManager.i("SyncManager: Server metadata not changed ($serverLastModified). Skipping deep sync.")
                    pref.saveLastSyncTime(currentTime)
                    pref.saveSyncProgress(null)
                    return SyncOutcome.SKIPPED_UP_TO_DATE
                }

                if (serverCapabilities.isOpenSubsonic) {
                    syncViaSearch3(
                        u = u,
                        t = t,
                        s = s,
                        ignoreSafetyGuard = ignoreSafetyGuard,
                        currentTime = currentTime,
                        serverLastModified = serverLastModified,
                        onProgress = onProgress,
                        onSyncComplete = onSyncComplete
                    )
                } else {
                    syncViaLegacyEndpoints(
                        ignoreSafetyGuard = ignoreSafetyGuard,
                        currentTime = currentTime,
                        serverLastModified = serverLastModified,
                        onProgress = onProgress,
                        onSyncComplete = onSyncComplete
                    )
                }
                return SyncOutcome.SYNCED_FULL
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching {
                    musicDao.clearSyncTempIds()
                    musicDao.repairAndCount()
                }.onFailure { cleanupEx ->
                    LogManager.e("SyncManager: cleanup/repair failed during sync error handling", cleanupEx)
                }
                pref.saveSyncError(e.message ?: e.toString())
                throw e
            } finally {
                if (!keepSyncingState) {
                    pref.saveIsSyncing(false)
                    pref.saveSyncProgress(null)
                }
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun fetchServerLastModified(
        u: String,
        t: String,
        s: String,
        ignoreLastModified: Boolean,
        hasLocalData: Boolean,
        lastServerModified: Long
    ): Long {
        return try {
            val ifModifiedSince = if (!ignoreLastModified && hasLocalData) lastServerModified else null
            val indexesResponse = api.getIndexes(u, t, s, ifModifiedSince = ifModifiedSince)
            if (indexesResponse.response.status == "ok") {
                val returnedLastModified = indexesResponse.response.indexes?.lastModified
                returnedLastModified ?: if (ifModifiedSince != null) lastServerModified else 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("SyncManager: Failed to get lastModified from getIndexes - ${e.message}")
            0L
        }
    }

    private suspend fun syncViaSearch3(
        u: String,
        t: String,
        s: String,
        ignoreSafetyGuard: Boolean,
        currentTime: Long,
        serverLastModified: Long,
        onProgress: (suspend (String) -> Unit)?,
        onSyncComplete: suspend (Long, Long) -> Unit
    ) {
        try {
            val p2 = "正在拉取元数据 (search3)..."
            onProgress?.invoke(p2)
            pref.saveSyncProgress(p2)
            LogManager.i("SyncManager: Syncing all data via paginated search3Stream(\"\")")

            val existingSongsMetadata = musicDao.getAllSongsMetadata().associateBy { it.id }
            val existingAlbumsMap = musicDao.getAlbums().associateBy { it.id }
            val localSnapshot = EntityCountSnapshot(
                artists = musicDao.getArtistsCount(),
                albums = musicDao.getAlbumsCount(),
                songs = musicDao.getSongsCount()
            )
            musicDao.clearSyncTempIds()

            val loopOutcome = streamSearch3Pages(
                u = u,
                t = t,
                s = s,
                existingAlbumsMap = existingAlbumsMap,
                existingSongsMetadata = existingSongsMetadata
            )

            val remoteCounts = EntityCountSnapshot(
                artists = musicDao.getSyncTempIdCountByType("artist"),
                albums = musicDao.getSyncTempIdCountByType("album"),
                songs = musicDao.getSyncTempIdCountByType("song")
            )
            verifySafetyGuard(localSnapshot, remoteCounts, ignoreSafetyGuard)

            var removalTargets: List<SongCleanupTarget> = emptyList()
            if (loopOutcome.offsetBugDetected) {
                LogManager.w("SyncManager: Pagination offset loop guard triggered, skipping surgical deletion to prevent accidental data loss.")
                val p4 = "正在更新统计信息..."
                onProgress?.invoke(p4)
                pref.saveSyncProgress(p4)
                runInTransaction {
                    musicDao.clearSyncTempIds()
                    musicDao.repairAndCount()
                }
            } else {
                val p3 = "正在清理冗余数据..."
                onProgress?.invoke(p3)
                pref.saveSyncProgress(p3)
                LogManager.d("SyncManager: Performing surgical sync-deletion via temp tables...")
                val p4 = "正在更新统计信息..."
                runInTransaction {
                    removalTargets = musicDao.getSongsNotInTempForCleanup()
                    musicDao.deleteSongsNotInTemp()
                    musicDao.deleteAlbumsNotInTemp()
                    musicDao.deleteArtistsNotInTemp()
                    musicDao.clearSyncTempIds()
                    musicDao.repairAndCount()
                }
                onProgress?.invoke(p4)
                pref.saveSyncProgress(p4)
                onSongsRemoved(removalTargets)
            }

            onSyncComplete(currentTime, serverLastModified)
            pref.saveSyncProgress(null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching {
                musicDao.clearSyncTempIds()
                musicDao.repairAndCount()
            }.onFailure { cleanupEx ->
                LogManager.e("SyncManager: cleanup/repair failed during search3 sync error handling", cleanupEx)
            }
            if (e is SafetyGuardException) throw e
            LogManager.e("SyncManager: search3 sync failed", e)
            throw e
        }
    }

    private suspend fun streamSearch3Pages(
        u: String,
        t: String,
        s: String,
        existingAlbumsMap: Map<String, de.lwp2070809.speculonic.data.db.entities.AlbumEntity>,
        existingSongsMetadata: Map<String, de.lwp2070809.speculonic.data.db.entities.SongMetadata>
    ): Search3LoopOutcome = coroutineScope {
        val artistChannel = Channel<List<ArtistEntity>>(capacity = Channel.BUFFERED)
        val albumChannel = Channel<List<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>>(capacity = Channel.BUFFERED)
        val songChannel = Channel<List<de.lwp2070809.speculonic.data.db.entities.SongEntity>>(capacity = Channel.BUFFERED)
        val tempIdChannel = Channel<List<SyncTempIdEntity>>(capacity = Channel.BUFFERED)

        val writerJob = launch(Dispatchers.IO) {
            try {
                var artistClosed = false
                var albumClosed = false
                var songClosed = false
                var tempIdClosed = false

                val pendingArtists = ArrayList<ArtistEntity>()
                val pendingAlbums = ArrayList<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>()
                val pendingSongs = ArrayList<de.lwp2070809.speculonic.data.db.entities.SongEntity>()
                val pendingTempIds = ArrayList<SyncTempIdEntity>()

                suspend fun flushBufferedWrites() {
                    if (pendingArtists.isEmpty() && pendingAlbums.isEmpty() && pendingSongs.isEmpty() && pendingTempIds.isEmpty()) {
                        return
                    }
                    // 分批事务提交可显著抑制全量同步期间 InvalidationTracker 的高频 UI 失效刷新
                    runInTransaction {
                        if (pendingArtists.isNotEmpty()) {
                            pendingArtists.chunked(BATCH_SIZE).forEach { musicDao.insertArtists(it) }
                        }
                        if (pendingAlbums.isNotEmpty()) {
                            pendingAlbums.chunked(BATCH_SIZE).forEach { musicDao.insertAlbums(it) }
                        }
                        if (pendingSongs.isNotEmpty()) {
                            pendingSongs.chunked(BATCH_SIZE).forEach { musicDao.insertSongs(it) }
                        }
                        if (pendingTempIds.isNotEmpty()) {
                            pendingTempIds.chunked(BATCH_SIZE).forEach { musicDao.insertSyncTempIds(it) }
                        }
                    }
                    pendingArtists.clear()
                    pendingAlbums.clear()
                    pendingSongs.clear()
                    pendingTempIds.clear()
                }

                while (!artistClosed || !albumClosed || !songClosed || !tempIdClosed) {
                    kotlinx.coroutines.selects.select {
                        if (!artistClosed) {
                            artistChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { pendingArtists.addAll(it) }
                                if (result.isClosed) artistClosed = true
                            }
                        }
                        if (!albumClosed) {
                            albumChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { pendingAlbums.addAll(it) }
                                if (result.isClosed) albumClosed = true
                            }
                        }
                        if (!songClosed) {
                            songChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { pendingSongs.addAll(it) }
                                if (result.isClosed) songClosed = true
                            }
                        }
                        if (!tempIdClosed) {
                            tempIdChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { pendingTempIds.addAll(it) }
                                if (result.isClosed) tempIdClosed = true
                            }
                        }
                    }
                    val totalBuffered = pendingArtists.size + pendingAlbums.size + pendingSongs.size + pendingTempIds.size
                    if (totalBuffered >= TRANSACTION_FLUSH_THRESHOLD) {
                        flushBufferedWrites()
                    }
                }
                flushBufferedWrites()
            } catch (t: Throwable) {
                artistChannel.close(t)
                albumChannel.close(t)
                songChannel.close(t)
                tempIdChannel.close(t)
                throw t
            }
        }

        var offsetBugDetected = false
        try {
            var artistOffset = 0
            var albumOffset = 0
            var songOffset = 0
            var hasMoreArtists = true
            var hasMoreAlbums = true
            var hasMoreSongs = true
            var page = 0

            val seenArtistIds = HashSet<String>()
            val seenAlbumIds = HashSet<String>()
            val seenSongIds = HashSet<String>()

            while ((hasMoreArtists || hasMoreAlbums || hasMoreSongs) && page < MAX_SEARCH3_PAGES) {
                page++
                val reqArtistCount = if (hasMoreArtists) ARTIST_PAGE_SIZE else 0
                val reqAlbumCount = if (hasMoreAlbums) ALBUM_PAGE_SIZE else 0
                val reqSongCount = if (hasMoreSongs) SONG_PAGE_SIZE else 0

                var pageStats = Search3PageStats()
                val responseBody = api.search3Stream(
                    query = "",
                    u = u,
                    t = t,
                    s = s,
                    artistCount = reqArtistCount,
                    artistOffset = artistOffset,
                    albumCount = reqAlbumCount,
                    albumOffset = albumOffset,
                    songCount = reqSongCount,
                    songOffset = songOffset
                )

                responseBody.use { body ->
                    ensureJsonResponse(body)
                    withContext(Dispatchers.IO) {
                        body.byteStream().use { stream ->
                            val streamingSerializer = SearchResult3StreamingSerializer(
                                artistChannel = artistChannel,
                                albumChannel = albumChannel,
                                songChannel = songChannel,
                                tempIdChannel = tempIdChannel,
                                existingAlbumsMap = existingAlbumsMap,
                                existingSongsMetadata = existingSongsMetadata,
                                entityMapper = entityMapper,
                                seenArtistIds = seenArtistIds,
                                seenAlbumIds = seenAlbumIds,
                                seenSongIds = seenSongIds,
                                onPageStats = { pageStats = it },
                                batchSize = BATCH_SIZE
                            )
                            val wrapperSerializer = SubsonicResponseStreamingSerializer(streamingSerializer)
                            json.decodeFromStream(wrapperSerializer, stream)
                        }
                    }
                }

                if (hasMoreArtists) {
                    val step = advancePaginationState("artist", artistOffset, reqArtistCount, pageStats.artistReceived, pageStats.artistAdded)
                    hasMoreArtists = step.hasMore
                    artistOffset = step.nextOffset
                    if (step.offsetBugDetected) offsetBugDetected = true
                }

                if (hasMoreAlbums) {
                    val step = advancePaginationState("album", albumOffset, reqAlbumCount, pageStats.albumReceived, pageStats.albumAdded)
                    hasMoreAlbums = step.hasMore
                    albumOffset = step.nextOffset
                    if (step.offsetBugDetected) offsetBugDetected = true
                }

                if (hasMoreSongs) {
                    val step = advancePaginationState("song", songOffset, reqSongCount, pageStats.songReceived, pageStats.songAdded)
                    hasMoreSongs = step.hasMore
                    songOffset = step.nextOffset
                    if (step.offsetBugDetected) offsetBugDetected = true
                }
            }

            if (hasMoreArtists || hasMoreAlbums || hasMoreSongs) {
                LogManager.w("SyncManager: Reached max search3 page limit ($MAX_SEARCH3_PAGES), stopping pagination safely.")
                offsetBugDetected = true
            }
        } finally {
            artistChannel.close()
            albumChannel.close()
            songChannel.close()
            tempIdChannel.close()
            writerJob.join()
        }

        Search3LoopOutcome(offsetBugDetected = offsetBugDetected)
    }

    private fun advancePaginationState(
        entityLabel: String,
        currentOffset: Int,
        requestedCount: Int,
        received: Int,
        added: Int
    ): PaginationStep {
        return when {
            received == 0 -> PaginationStep(hasMore = false, nextOffset = currentOffset, offsetBugDetected = false)
            added == 0 -> {
                LogManager.w("SyncManager: Server ignored ${entityLabel}Offset=$currentOffset, stopping $entityLabel pagination.")
                PaginationStep(hasMore = false, nextOffset = currentOffset, offsetBugDetected = true)
            }
            received < requestedCount -> PaginationStep(
                hasMore = false,
                nextOffset = currentOffset + received,
                offsetBugDetected = false
            )
            else -> PaginationStep(
                hasMore = true,
                nextOffset = currentOffset + received,
                offsetBugDetected = false
            )
        }
    }

    private fun ensureJsonResponse(body: ResponseBody) {
        val contentType = body.contentType()
        val type = contentType?.type ?: ""
        val subtype = contentType?.subtype ?: ""
        if (type.equals("text", ignoreCase = true) || subtype.contains("html", ignoreCase = true)) {
            throw IllegalStateException("API returned HTML/Text instead of JSON. Server might be under maintenance or misconfigured.")
        }
        val peekedPrefix = runCatching {
            body.source().peek().readUtf8(minOf(HTML_PEEK_BYTES, body.contentLength().takeIf { it > 0 } ?: HTML_PEEK_BYTES))
        }.getOrDefault("")
        val trimmed = peekedPrefix.trimStart()
        if (trimmed.startsWith("<") && !trimmed.startsWith("<?xml", ignoreCase = true)) {
            throw IllegalStateException("API returned HTML page instead of JSON response.")
        }
    }

    private suspend fun syncViaLegacyEndpoints(
        ignoreSafetyGuard: Boolean,
        currentTime: Long,
        serverLastModified: Long,
        onProgress: (suspend (String) -> Unit)?,
        onSyncComplete: suspend (Long, Long) -> Unit
    ) {
        val p5 = "正在使用传统模式同步..."
        onProgress?.invoke(p5)
        pref.saveSyncProgress(p5)

        val localSnapshot = EntityCountSnapshot(
            artists = musicDao.getArtistsCount(),
            albums = musicDao.getAlbumsCount(),
            songs = musicDao.getSongsCount()
        )
        musicDao.clearSyncTempIds()
        val syncResult = syncAllArtistsAndSongs()
        syncAllAlbums(recordTempIds = true)

        var removalTargets: List<SongCleanupTarget> = emptyList()
        if (syncResult.totalCount > 0 && syncResult.failCount == 0) {
            val remoteCounts = EntityCountSnapshot(
                artists = musicDao.getSyncTempIdCountByType("artist"),
                albums = musicDao.getSyncTempIdCountByType("album"),
                songs = musicDao.getSyncTempIdCountByType("song")
            )
            verifySafetyGuard(localSnapshot, remoteCounts, ignoreSafetyGuard, isLegacy = true)
            LogManager.d("SyncManager: Performing legacy surgical sync-deletion via temp tables (zero directory failure)...")
            runInTransaction {
                removalTargets = musicDao.getSongsNotInTempForCleanup()
                musicDao.deleteSongsNotInTemp()
                musicDao.deleteAlbumsNotInTemp()
                musicDao.deleteArtistsNotInTemp()
                musicDao.clearSyncTempIds()
                musicDao.repairAndCount()
            }
            onSongsRemoved(removalTargets)
        } else {
            if (syncResult.failCount > 0) {
                val skipMsg = "SyncManager: Directory fetch encountered failures " +
                    "(${syncResult.failCount}/${syncResult.totalCount} failed). Skipped surgical deletion."
                LogManager.w(skipMsg)
            }
            runInTransaction {
                musicDao.clearSyncTempIds()
                musicDao.repairAndCount()
            }
        }
        onSyncComplete(currentTime, serverLastModified)
        pref.saveSyncProgress(null)
    }

    private fun verifySafetyGuard(
        local: EntityCountSnapshot,
        remote: EntityCountSnapshot,
        ignoreSafetyGuard: Boolean,
        isLegacy: Boolean = false
    ) {
        if (ignoreSafetyGuard) return
        val modePrefix = if (isLegacy) "传统模式" else "全量同步"
        checkDimensionThreshold(local.songs, remote.songs, "歌曲", modePrefix)
        checkDimensionThreshold(local.albums, remote.albums, "专辑", modePrefix)
        checkDimensionThreshold(local.artists, remote.artists, "艺术家", modePrefix)
    }

    private fun checkDimensionThreshold(localCount: Int, remoteCount: Int, label: String, modePrefix: String) {
        if (localCount < MIN_ENTITIES_FOR_SAFETY) return
        val dropThreshold = localCount * SAFETY_GUARD_DROP_RATIO
        if (remoteCount < dropThreshold) {
            throw SafetyGuardException(
                "安全保护触发：${modePrefix}返回${label}数 ($remoteCount) 远低于本地基数 ($localCount)。同步已中止以防止误删。"
            )
        }
    }

    
    suspend fun quickSync(
        onProgress: (suspend (String) -> Unit)? = null,
        onComplete: suspend (Long) -> Unit
    ) {
        if (!syncMutex.tryLock()) {
            LogManager.i("SyncManager: sync is already running, skipping quickSync.")
            return
        }
        try {
            pref.saveIsSyncing(true)
            val currentTime = System.currentTimeMillis()
            onProgress?.invoke("正在执行轻量同步...")
            
            
            syncAllAlbums(type = "newest")
            musicDao.repairAndCount()
            
            onComplete(currentTime)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("SyncManager: quickSync failed", e)
            throw e
        } finally {
            runCatching { musicDao.clearSyncTempIds() }
            pref.saveIsSyncing(false)
            pref.saveSyncProgress(null)
            syncMutex.unlock()
        }
    }

    suspend fun syncAllAlbums(type: String = "alphabeticalByName", recordTempIds: Boolean = false) {
        var offset = 0
        val pageSize = 500
        while (true) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
            val response = api.getAlbumList2(u, t, s, type = type, size = pageSize, offset = offset)
            val albums = response.response.albumList2?.album ?: emptyList()
            if (albums.isEmpty()) break
            
            saveAlbumList(type, albums, offset, recordTempIds = recordTempIds)
            
            offset += albums.size
            if (albums.size < pageSize) break
        }
    }

    private suspend fun saveAlbumList(
        listType: String,
        albums: List<Album>,
        offset: Int = 0,
        starredOverride: Boolean? = null,
        recordTempIds: Boolean = false
    ) {
        if (albums.isEmpty()) return
        
        val albumIds = albums.map { it.id }
        val existingAlbumsMap = albumIds.chunked(500)
            .flatMap { musicDao.getAlbumsByIds(it) }
            .associateBy { it.id }
        
        val albumEntities = mutableListOf<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>()
        val listItems = mutableListOf<de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity>()
        
        albums.forEachIndexed { index, album ->
            val existing = existingAlbumsMap[album.id]
            val isStarred = starredOverride ?: (album.starred != null)
            albumEntities.add(entityMapper.albumToEntity(album, existing = existing, isStarred = isStarred))
            listItems.add(de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity(listType, album.id, index + offset))
        }
        
        if (recordTempIds && albums.isNotEmpty()) {
            musicDao.insertSyncTempIds(albums.map { SyncTempIdEntity(it.id, "album") })
        }

        if (listType == "starred") {
            musicDao.syncStarredAlbums(listType, albumEntities, listItems)
        } else {
            if (offset == 0) {
                musicDao.updateAlbumList(listType, albumEntities, listItems)
            } else {
                albumEntities.chunked(500).forEach { musicDao.insertAlbums(it) }
                listItems.chunked(500).forEach { musicDao.insertAlbumListItems(it) }
            }
        }
    }

    data class LegacySyncResult(val totalCount: Int, val failCount: Int, val songCount: Int)

    private sealed class SyncDirResult {
        data class Success(val subDirs: List<Pair<String, String?>>, val songCount: Int) : SyncDirResult()
        object Failure : SyncDirResult()
    }

    suspend fun syncAllArtistsAndSongs(): LegacySyncResult {
        val (u, t, s) = authManager.getAuthParams()
        val response = api.getIndexes(u, t, s)
        val indexes = response.response.indexes ?: return LegacySyncResult(0, 0, 0)
        
        val allArtists = indexes.index.flatMap { it.artist }
        if (allArtists.isNotEmpty()) {
            allArtists.chunked(500).forEach { chunk ->
                musicDao.insertArtists(chunk.map { ArtistEntity(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount ?: 0) })
                musicDao.insertSyncTempIds(chunk.map { SyncTempIdEntity(it.id, "artist") })
            }
        }

        val queue = mutableListOf<Pair<String, String?>>()
        allArtists.forEach { queue.add(it.id to it.id) }
        
        var failCount = 0
        var totalCount = 0
        var totalSongCount = 0
        
        while (queue.isNotEmpty()) {
            val chunk = queue.take(10)
            queue.subList(0, chunk.size).clear()
            totalCount += chunk.size
            
            val nextDirs = kotlinx.coroutines.coroutineScope {
                chunk.map { (id, artistId) ->
                    async {
                        syncSingleDirectory(id, artistId, u, t, s)
                    }
                }.awaitAll()
            }
            
            nextDirs.forEach { result ->
                when (result) {
                    is SyncDirResult.Success -> {
                        queue.addAll(result.subDirs)
                        totalSongCount += result.songCount
                    }
                    is SyncDirResult.Failure -> failCount++
                }
            }
        }
        
        if (totalCount > 0 && (failCount.toDouble() / totalCount) > 0.5) {
            throw java.io.IOException("Legacy sync failure rate too high: $failCount/$totalCount directories failed")
        }
        return LegacySyncResult(totalCount, failCount, totalSongCount)
    }

    private suspend fun syncSingleDirectory(id: String, artistId: String?, u: String, t: String, s: String): SyncDirResult {
        try {
            val response = api.getMusicDirectory(id, u, t, s)
            response.response.ensureOk()
            val directory = response.response.directory ?: return SyncDirResult.Success(emptyList(), 0)
            val children = directory.child
            
            val songs = children.filter { !it.isDir }
            val subDirs = children.filter { it.isDir }

            if (songs.isNotEmpty()) {
                val songIds = songs.map { it.id }
                val existingSongsMap = songIds.chunked(500)
                    .flatMap { musicDao.getSongsByIds(it) }
                    .associateBy { it.id }

                val entities = songs.map { song ->
                    val local = existingSongsMap[song.id]
                    val isStarred = song.starred != null
                    val lastUpdated = if (isStarred && local?.starred != true) null else local?.lastUpdated
                    entityMapper.songToEntity(song,
                        albumId = song.albumId ?: id, 
                        artistId = artistId ?: song.artistId,
                        localUri = local?.localUri, 
                        isCached = local?.isFullyCached ?: false,
                        isTranscoded = local?.isTranscoded ?: false,
                        parentId = id,
                        isStarred = isStarred,
                        lastUpdated = lastUpdated
                    )
                }
                runInTransaction {
                    entities.chunked(500).forEach { musicDao.insertSongs(it) }
                    musicDao.insertSyncTempIds(songs.map { SyncTempIdEntity(it.id, "song") })

                    val albumIds = entities.mapNotNull { it.albumId }.distinct()
                    if (albumIds.isNotEmpty()) {
                        musicDao.insertSyncTempIds(albumIds.map { SyncTempIdEntity(it, "album") })
                    }

                    val existingAlbums = if (albumIds.isNotEmpty()) {
                        albumIds.chunked(500).flatMap { musicDao.getAlbumsByIds(it) }.associateBy { it.id }
                    } else {
                        emptyMap()
                    }
                    val albumEntitiesToUpsert = entities.groupBy { it.albumId }.mapNotNull { (albumId, songsInAlbum) ->
                        albumId ?: return@mapNotNull null
                        val firstSong = songsInAlbum.first()
                        val existingAlbum = existingAlbums[albumId]
                        de.lwp2070809.speculonic.data.db.entities.AlbumEntity(
                            id = albumId,
                            name = firstSong.album ?: "Unknown Album",
                            artist = firstSong.artist ?: "Unknown Artist",
                            artistId = artistId ?: firstSong.artistId,
                            coverArt = firstSong.coverArt,
                            songCount = songsInAlbum.size,
                            starred = existingAlbum?.starred ?: false,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    if (albumEntitiesToUpsert.isNotEmpty()) {
                        albumEntitiesToUpsert.chunked(500).forEach { musicDao.insertAlbums(it) }
                    }
                }
            }

            return SyncDirResult.Success(subDirs.map { it.id to artistId }, songs.size)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("SyncManager: syncSingleDirectory failed for id=$id", e)
            return SyncDirResult.Failure
        }
    }

    companion object {
        private const val SYNC_DEBOUNCE_MILLIS = 12 * 60 * 60 * 1000L
        private const val BATCH_SIZE = 500
        private const val TRANSACTION_FLUSH_THRESHOLD = 4000
        private const val SAFETY_GUARD_DROP_RATIO = 0.7
        private const val MIN_ENTITIES_FOR_SAFETY = 10
        private const val ARTIST_PAGE_SIZE = 1000
        private const val ALBUM_PAGE_SIZE = 2000
        private const val SONG_PAGE_SIZE = 5000
        private const val MAX_SEARCH3_PAGES = 200
        private const val HTML_PEEK_BYTES = 128L
    }
}
