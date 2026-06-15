package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

@OptIn(ExperimentalSerializationApi::class)
class SyncManager(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val pref: PreferencesManager,
    private val entityMapper: EntityMapper
) {
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
    ) {
        if (!syncMutex.tryLock()) {
            LogManager.i("SyncManager: sync is already running, skipping duplicate request.")
            return
        }
        try {
            val lastSync = pref.lastSyncTime.first()
        val lastServerModified = pref.serverLastModified.first()
        val currentTime = System.currentTimeMillis()
        
        if (!forceRefresh && (currentTime - lastSync < SYNC_DEBOUNCE_MILLIS) && hasLocalData) {
            LogManager.d("SyncManager: syncAllData skipped due to rate limiting")
            return
        }

        pref.saveIsSyncing(true)
        pref.saveSyncProgress(null)

        try {
            val (u, t, s) = authManager.getAuthParams()

            val progressMsg = "正在检查服务器更新..."
            onProgress?.invoke(progressMsg)
            pref.saveSyncProgress(progressMsg)
            val serverLastModified: Long = try {
                val indexesResponse = api.getIndexes(u, t, s)
                indexesResponse.response.indexes?.lastModified ?: 0L
            } catch (e: Exception) {
                LogManager.w("SyncManager: Failed to get lastModified from getIndexes - ${e.message}")
                0L
            }

            if (!ignoreLastModified && serverLastModified != 0L && serverLastModified == lastServerModified && hasLocalData) {
                LogManager.i("SyncManager: Server metadata not changed ($serverLastModified). Skipping deep sync.")
                pref.saveLastSyncTime(currentTime)
                pref.saveSyncProgress(null)
                return
            }
            
            if (serverCapabilities.isOpenSubsonic) {
                try {
                    val p2 = "正在拉取元数据 (search3)..."
                    onProgress?.invoke(p2)
                    pref.saveSyncProgress(p2)
                    LogManager.i("SyncManager: Syncing all data via search3Stream(\"\")")
                    
                    val responseBody = api.search3Stream(
                        query = "",
                        u = u,
                        t = t,
                        s = s,
                        artistCount = 10000,
                        albumCount = 10000,
                        songCount = 100000
                    )

                    val contentType = responseBody.contentType()?.type ?: ""
                    if (contentType.equals("text", ignoreCase = true) || responseBody.contentType()?.subtype?.contains("html", ignoreCase = true) == true) {
                        throw IllegalStateException("API returned HTML instead of JSON. Server might be under maintenance or misconfigured.")
                    }

                    var serverSongCount = 0
                    
                    val existingSongsMetadata = musicDao.getAllSongsMetadata().associateBy { it.id }
                    val existingAlbumsMap = musicDao.getAlbums().associateBy { it.id }

                    musicDao.clearSyncTempIds()

                    coroutineScope {
                        
                        val artistChannel = Channel<List<ArtistEntity>>(capacity = Channel.BUFFERED)
                        val albumChannel = Channel<List<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>>(capacity = Channel.BUFFERED)
                        val songChannel = Channel<List<de.lwp2070809.speculonic.data.db.entities.SongEntity>>(capacity = Channel.BUFFERED)
                        val tempIdChannel = Channel<List<SyncTempIdEntity>>(capacity = Channel.BUFFERED)

                        val consumers = listOf(
                            launch(Dispatchers.IO) { for (chunk in artistChannel) musicDao.insertArtists(chunk) },
                            launch(Dispatchers.IO) { for (chunk in albumChannel) musicDao.insertAlbums(chunk) },
                            launch(Dispatchers.IO) { for (chunk in songChannel) musicDao.insertSongs(chunk) },
                            launch(Dispatchers.IO) { for (chunk in tempIdChannel) musicDao.insertSyncTempIds(chunk) }
                        )

                        withContext(Dispatchers.IO) {
                            responseBody.byteStream().use { stream ->
                                val streamingSerializer = SearchResult3StreamingSerializer(
                                    artistChannel = artistChannel,
                                    albumChannel = albumChannel,
                                    songChannel = songChannel,
                                    tempIdChannel = tempIdChannel,
                                    existingAlbumsMap = existingAlbumsMap,
                                    existingSongsMetadata = existingSongsMetadata,
                                    entityMapper = entityMapper,
                                    onSongCount = { serverSongCount = it },
                                    batchSize = BATCH_SIZE
                                )
                                
                                val wrapperSerializer = SubsonicResponseStreamingSerializer(streamingSerializer)
                                json.decodeFromStream(wrapperSerializer, stream)
                            }
                        }

                        artistChannel.close()
                        albumChannel.close()
                        songChannel.close()
                        tempIdChannel.close()
                        consumers.joinAll()
                    }

                    
                    val localSongCount = musicDao.getSongsCount()
                    if (!ignoreSafetyGuard && localSongCount > 0) {
                        val dropThreshold = maxOf(localSongCount * SAFETY_GUARD_DROP_RATIO, 10.0)
                        if (serverSongCount < dropThreshold) {
                            throw SafetyGuardException("安全保护触发：服务器返回歌曲数 ($serverSongCount) 远低于本地基数 ($localSongCount)。同步已中止以防止误删。")
                        }
                    }

                    val p3 = "正在清理冗余数据..."
                    onProgress?.invoke(p3)
                    pref.saveSyncProgress(p3)
                    LogManager.d("SyncManager: Performing surgical sync-deletion via temp tables...")
                    musicDao.deleteSongsNotInTemp()
                    musicDao.deleteAlbumsNotInTemp()
                    musicDao.deleteArtistsNotInTemp()
                    musicDao.clearSyncTempIds()
                    
                    val p4 = "正在更新统计信息..."
                    onProgress?.invoke(p4)
                    pref.saveSyncProgress(p4)
                    musicDao.repairAndCount()
                    
                    onSyncComplete(currentTime, serverLastModified)
                    pref.saveSyncProgress(null)
                    return
                } catch (e: Exception) {
                    musicDao.clearSyncTempIds()
                    if (e is SafetyGuardException) throw e
                    LogManager.e("SyncManager: search3 sync failed", e)
                    throw e 
                }
            }
            
            
            val p5 = "正在使用传统模式同步..."
            onProgress?.invoke(p5)
            pref.saveSyncProgress(p5)
            syncAllArtistsAndSongs()
            syncAllAlbums()
            musicDao.repairAndCount()
            onSyncComplete(currentTime, serverLastModified)
            pref.saveSyncProgress(null)
        } catch (e: Exception) {
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
            
            
            onComplete(currentTime)
        } catch (e: Exception) {
            LogManager.e("SyncManager: quickSync failed", e)
            throw e
        } finally {
            pref.saveIsSyncing(false)
            pref.saveSyncProgress(null)
            syncMutex.unlock()
        }
    }

    suspend fun syncAllAlbums(type: String = "alphabeticalByName") {
        var offset = 0
        val pageSize = 500
        while (true) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
            val response = api.getAlbumList2(u, t, s, type = type, size = pageSize, offset = offset)
            val albums = response.response.albumList2?.album ?: emptyList()
            if (albums.isEmpty()) break
            
            saveAlbumList(type, albums, offset)
            
            offset += albums.size
            if (albums.size < pageSize) break
        }
    }

    private suspend fun saveAlbumList(
        listType: String,
        albums: List<Album>,
        offset: Int = 0,
        starredOverride: Boolean? = null
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
            val isStarred = starredOverride ?: if (album.starred != null) true else existing?.starred
            albumEntities.add(entityMapper.albumToEntity(album, existing = existing, isStarred = isStarred))
            listItems.add(de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity(listType, album.id, index + offset))
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

    suspend fun syncAllArtistsAndSongs() {
        val (u, t, s) = authManager.getAuthParams()
        try {
            val response = api.getIndexes(u, t, s)
            val indexes = response.response.indexes ?: return
            
            val allArtists = indexes.index.flatMap { it.artist }
            if (allArtists.isNotEmpty()) {
                allArtists.chunked(500).forEach { chunk ->
                    musicDao.insertArtists(chunk.map { ArtistEntity(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount ?: 0) })
                }
            }

            
            val queue = mutableListOf<Pair<String, String?>>()
            allArtists.forEach { queue.add(it.id to it.id) }
            
            while (queue.isNotEmpty()) {
                val chunk = queue.take(10)
                queue.subList(0, chunk.size).clear()
                
                val nextDirs = kotlinx.coroutines.coroutineScope {
                    chunk.map { (id, artistId) ->
                        async {
                            syncSingleDirectory(id, artistId, u, t, s)
                        }
                    }.awaitAll().flatten()
                }
                queue.addAll(nextDirs)
            }
        } catch (e: Exception) {
            LogManager.e("SyncManager: syncAllArtistsAndSongs failed", e)
        }
    }

    private suspend fun syncSingleDirectory(id: String, artistId: String?, u: String, t: String, s: String): List<Pair<String, String?>> {
        try {
            val response = api.getMusicDirectory(id, u, t, s)
            val directory = response.response.directory ?: return emptyList()
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
                    entityMapper.songToEntity(song, 
                        albumId = song.albumId ?: id, 
                        artistId = artistId ?: song.artistId,
                        localUri = local?.localUri, 
                        isCached = local?.isFullyCached ?: false,
                        parentId = id,
                        isStarred = if (song.starred != null) true else local?.starred,
                        lastUpdated = local?.lastUpdated
                    )
                }
                entities.chunked(500).forEach { musicDao.insertSongs(it) }
                
                entities.groupBy { it.albumId }.forEach { (albumId, songsInAlbum) ->
                    if (albumId != null) {
                        val firstSong = songsInAlbum.first()
                        val existingAlbum = musicDao.getAlbumById(albumId)
                        val albumEntity = de.lwp2070809.speculonic.data.db.entities.AlbumEntity(
                            id = albumId,
                            name = firstSong.album ?: "Unknown Album",
                            artist = firstSong.artist ?: "Unknown Artist",
                            artistId = artistId ?: firstSong.artistId,
                            coverArt = firstSong.coverArt,
                            songCount = songsInAlbum.size,
                            starred = existingAlbum?.starred ?: false,
                            lastUpdated = System.currentTimeMillis()
                        )
                        musicDao.insertAlbums(listOf(albumEntity))
                    }
                }
            }

            return subDirs.map { it.id to artistId }
        } catch (e: Exception) {
            LogManager.e("SyncManager: syncSingleDirectory failed for id=$id", e)
            return emptyList()
        }
    }

    companion object {
        private val syncMutex = Mutex()
        private const val SYNC_DEBOUNCE_MILLIS = 12 * 60 * 60 * 1000L 
        private const val BATCH_SIZE = 500
        private const val SAFETY_GUARD_THRESHOLD = 1000
        private const val SAFETY_GUARD_DROP_RATIO = 0.7
    }
}
