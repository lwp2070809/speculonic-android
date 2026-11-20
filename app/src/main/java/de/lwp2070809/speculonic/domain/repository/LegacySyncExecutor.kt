package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.ensureOk
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

internal class LegacySyncExecutor(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val pref: PreferencesManager,
    private val entityMapper: EntityMapper,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit
) {
    private data class DirectoryCounts(val total: Int, val failed: Int)

    private sealed class SyncDirResult {
        data class Success(val subDirs: List<Pair<String, String?>>) : SyncDirResult()
        object Failure : SyncDirResult()
    }

    suspend fun execute(
        params: SyncExecutionParams,
        verifySafetyGuard: (EntityCountSnapshot, EntityCountSnapshot, Boolean, Boolean) -> Unit,
        performSurgicalCleanup: suspend (suspend (String) -> Unit, (suspend (String) -> Unit)?) -> Unit
    ) {
        val statusMsg = "正在使用传统模式同步..."
        params.onProgress?.invoke(statusMsg)
        pref.saveSyncProgress(statusMsg)

        val localSnapshot = EntityCountSnapshot(
            artists = musicDao.getArtistsCount(),
            albums = musicDao.getAlbumsCount(),
            songs = musicDao.getSongsCount()
        )
        musicDao.clearSyncTempIds()
        val directories = syncAllArtistsAndSongs()
        val indexedSongCount = musicDao.getSyncTempIdCountByType("song")
        syncAllAlbums(indexedSongCount = indexedSongCount.takeIf { directories.failed == 0 })

        if (directories.failed > 0) {
            throw IOException("Legacy sync incomplete: ${directories.failed}/${directories.total} directories failed")
        }
        val remoteCounts = EntityCountSnapshot(
            artists = musicDao.getSyncTempIdCountByType("artist"),
            albums = musicDao.getSyncTempIdCountByType("album"),
            songs = indexedSongCount
        )
        verifySafetyGuard(localSnapshot, remoteCounts, params.ignoreSafetyGuard, true)
        LogManager.d("SyncManager: Performing legacy surgical sync-deletion via temp tables (zero directory failure)...")
        performSurgicalCleanup({}, null)
        params.onSyncComplete(params.currentTime, params.serverLastModified)
        pref.saveSyncProgress(null)
    }

    suspend fun syncAllAlbums(type: String = "alphabeticalByName", indexedSongCount: Int? = null) {
        var offset = 0
        val seenAlbumIds = mutableSetOf<String>()
        val recordTempIds = indexedSongCount != null
        while (true) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
            val response = api.getAlbumList2(u, t, s, type = type, size = ALBUM_PAGE_SIZE, offset = offset)
            val albums = checkNotNull(response.response.ensureOk().albumList2) { "Missing albumList2 in response" }.album
            if (albums.isEmpty()) return
            if (indexedSongCount == 0 && albums.any { (it.songCount ?: 0) > 0 || it.song.isNotEmpty() }) {
                throw IOException("Legacy sync incomplete: albums contain songs missing from the directory index")
            }
            val hasPaginationIssue = if (recordTempIds) {
                albums.any { !seenAlbumIds.add(it.id) }
            } else {
                !seenAlbumIds.addAll(albums.map { it.id })
            }
            if (hasPaginationIssue) {
                throw IncompletePaginationException("Overlapping album page at offset $offset")
            }
            saveAlbumList(type, albums, offset, recordTempIds = recordTempIds)
            if (albums.size < ALBUM_PAGE_SIZE) return
            offset += albums.size
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
        val existingAlbumsMap = albumIds.chunked(BATCH_SIZE)
            .flatMap { musicDao.getAlbumsByIds(it) }
            .associateBy { it.id }

        val albumEntities = mutableListOf<AlbumEntity>()
        val listItems = mutableListOf<AlbumListItemEntity>()

        albums.forEachIndexed { index, album ->
            val existing = existingAlbumsMap[album.id]
            val isStarred = starredOverride ?: (album.starred != null)
            albumEntities.add(entityMapper.albumToEntity(album, existing = existing, isStarred = isStarred))
            listItems.add(AlbumListItemEntity(listType, album.id, index + offset))
        }

        if (recordTempIds) {
            musicDao.insertSyncTempIds(albums.map { SyncTempIdEntity(it.id, "album") })
        }

        if (listType == "starred") {
            musicDao.syncStarredAlbums(listType, albumEntities, listItems)
        } else if (offset == 0) {
            musicDao.updateAlbumList(listType, albumEntities, listItems)
        } else {
            albumEntities.chunked(BATCH_SIZE).forEach { musicDao.insertAlbums(it) }
            listItems.chunked(BATCH_SIZE).forEach { musicDao.insertAlbumListItems(it) }
        }
    }

    private suspend fun syncAllArtistsAndSongs(): DirectoryCounts {
        val (u, t, s) = authManager.getAuthParams()
        val response = api.getIndexes(u, t, s)
        val indexes = response.response.ensureOk().indexes ?: error("Missing indexes in getIndexes response")

        val allArtists = indexes.index.flatMap { it.artist }.distinctBy { it.id }
        if (allArtists.isNotEmpty()) {
            allArtists.chunked(BATCH_SIZE).forEach { chunk ->
                musicDao.insertArtists(
                    chunk.map { ArtistEntity(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount ?: 0) }
                )
                musicDao.insertSyncTempIds(chunk.map { SyncTempIdEntity(it.id, "artist") })
            }
        }

        val seen = allArtists.mapTo(mutableSetOf()) { it.id }
        val queue = ArrayDeque<Pair<String, String?>>(seen.map { it to it })
        indexes.shortcut.filter { seen.add(it.id) }.forEach { queue.add(it.id to null) }
        val rootSongs = indexes.child.filterNot { it.isDir }
        rootSongs.chunked(BATCH_SIZE).forEach { persistDirectorySongs(null, null, it) }
        indexes.child.filter { it.isDir && seen.add(it.id) }.forEach { queue.add(it.id to it.artistId) }
        var failCount = 0
        var totalCount = 0

        while (queue.isNotEmpty()) {
            val chunk = List(minOf(DIRECTORY_CONCURRENCY, queue.size)) { queue.removeFirst() }
            totalCount += chunk.size

            val nextDirs = coroutineScope {
                chunk.map { (id, artistId) -> async { syncSingleDirectory(id, artistId, u, t, s) } }.awaitAll()
            }
            nextDirs.forEach { result ->
                when (result) {
                    is SyncDirResult.Success -> result.subDirs.filterTo(queue) { seen.add(it.first) }
                    is SyncDirResult.Failure -> failCount++
                }
            }
        }

        return DirectoryCounts(total = totalCount, failed = failCount)
    }

    private suspend fun syncSingleDirectory(
        id: String,
        artistId: String?,
        u: String,
        t: String,
        s: String
    ): SyncDirResult {
        return try {
            val response = api.getMusicDirectory(id, u, t, s)
            response.response.ensureOk()
            val directory = response.response.directory ?: return SyncDirResult.Failure
            val children = directory.child
            val songs = children.filter { !it.isDir }
            val subDirs = children.filter { it.isDir }

            if (songs.isNotEmpty()) {
                persistDirectorySongs(id, artistId, songs)
            }
            SyncDirResult.Success(subDirs.map { it.id to (artistId ?: it.artistId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("SyncManager: syncSingleDirectory failed for id=$id", e)
            SyncDirResult.Failure
        }
    }

    private suspend fun persistDirectorySongs(
        directoryId: String?,
        artistId: String?,
        songs: List<Song>
    ) {
        val songIds = songs.map { it.id }
        val existingSongsMap = songIds.chunked(BATCH_SIZE)
            .flatMap { musicDao.getSongsByIds(it) }
            .associateBy { it.id }

        val entities = songs.map { song ->
            val local = existingSongsMap[song.id]
            val isStarred = song.starred != null
            val lastUpdated = if (isStarred && local?.starred != true) null else local?.lastUpdated
            entityMapper.songToEntity(
                song = song,
                overrides = SongEntityOverrides(
                    albumId = song.albumId ?: directoryId,
                    artistId = song.artistId ?: artistId,
                    parentId = directoryId,
                    localUri = local?.localUri,
                    isCached = local?.isFullyCached ?: false,
                    isTranscoded = local?.isTranscoded ?: false,
                    isStarred = isStarred,
                    lastUpdated = lastUpdated
                )
            )
        }

        runInTransaction {
            insertSongsAndInferredAlbums(entities, songs, artistId)
        }
    }

    private suspend fun insertSongsAndInferredAlbums(
        entities: List<SongEntity>,
        songs: List<Song>,
        artistId: String?
    ) {
        entities.chunked(BATCH_SIZE).forEach { musicDao.insertSongs(it) }
        musicDao.insertSyncTempIds(songs.map { SyncTempIdEntity(it.id, "song") })

        val albumIds = entities.mapNotNull { it.albumId }.distinct()
        if (albumIds.isNotEmpty()) {
            musicDao.insertSyncTempIds(albumIds.map { SyncTempIdEntity(it, "album") })
        }

        val existingAlbums = if (albumIds.isNotEmpty()) {
            albumIds.chunked(BATCH_SIZE).flatMap { musicDao.getAlbumsByIds(it) }.associateBy { it.id }
        } else {
            emptyMap()
        }
        val albumEntitiesToUpsert = entities.groupBy { it.albumId }.mapNotNull { (albumId, songsInAlbum) ->
            albumId ?: return@mapNotNull null
            val firstSong = songsInAlbum.first()
            val existingAlbum = existingAlbums[albumId]
            AlbumEntity(
                id = albumId,
                name = firstSong.album ?: "Unknown Album",
                artist = firstSong.artist ?: "Unknown Artist",
                artistId = firstSong.artistId ?: artistId,
                coverArt = firstSong.coverArt,
                songCount = songsInAlbum.size,
                starred = existingAlbum?.starred ?: false,
                lastUpdated = System.currentTimeMillis()
            )
        }
        if (albumEntitiesToUpsert.isNotEmpty()) {
            albumEntitiesToUpsert.chunked(BATCH_SIZE).forEach { musicDao.insertAlbums(it) }
        }
    }

    private companion object {
        const val ALBUM_PAGE_SIZE = 500
        const val BATCH_SIZE = 500
        const val DIRECTORY_CONCURRENCY = 10
    }
}
