package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.interceptor.ServerUnavailableException
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.isSubsonicAuthError
import de.lwp2070809.speculonic.network.model.ensureOk
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

internal class Id3SyncExecutor(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val pref: PreferencesManager,
    private val entityMapper: EntityMapper,
    private val albumListHelper: AlbumListSyncHelper,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit
) {
    suspend fun execute(onProgress: (suspend (String) -> Unit)?) {
        syncAllArtists()
        val albumIds = albumListHelper.syncAllAlbums(recordTempIds = true)
        val failedAlbums = syncSongsByAlbums(albumIds, onProgress)
        if (failedAlbums > 0) {
            throw IOException("ID3 sync incomplete: $failedAlbums/${albumIds.size} albums failed")
        }
    }

    private suspend fun syncAllArtists() {
        val (u, t, s) = authManager.getAuthParams()
        val response = api.getArtists(u, t, s)
        val artists = checkNotNull(response.response.ensureOk().artists) {
            "Missing artists in getArtists response"
        }
        val allArtists = artists.index.flatMap { it.artist }.distinctBy { it.id }
        if (allArtists.isEmpty()) return

        runInTransaction {
            allArtists.chunked(BATCH_SIZE).forEach { chunk ->
                musicDao.insertArtists(
                    chunk.map {
                        ArtistEntity(
                            id = it.id,
                            name = it.name,
                            coverArt = it.coverArt,
                            albumCount = it.albumCount ?: 0
                        )
                    }
                )
                musicDao.insertSyncTempIds(chunk.map { SyncTempIdEntity(it.id, "artist") })
            }
        }
    }

    private suspend fun syncSongsByAlbums(
        albumIds: List<String>,
        onProgress: (suspend (String) -> Unit)?
    ): Int {
        if (albumIds.isEmpty()) return 0
        val seenSongIds = HashSet<String>()
        var failedCount = 0
        var consecutiveFailures = 0
        var processedCount = 0

        for (batchIds in albumIds.chunked(ALBUM_COMMIT_BATCH_SIZE)) {
            val fetchedAlbums = fetchAlbumsBatch(batchIds)
            val resolvedAlbums = ArrayList<Album>(batchIds.size)
            for (album in fetchedAlbums) {
                if (album == null) {
                    failedCount++
                    consecutiveFailures++
                } else {
                    consecutiveFailures = 0
                    resolvedAlbums.add(album)
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_ALBUM_FAILURES) {
                    throw IOException("ID3 sync aborted after $consecutiveFailures consecutive album failures")
                }
            }
            if (resolvedAlbums.isNotEmpty()) {
                persistAlbumsAndSongs(resolvedAlbums, seenSongIds)
            }
            processedCount += batchIds.size
            val progressMsg = "正在拉取元数据 (ID3: $processedCount/${albumIds.size})..."
            onProgress?.invoke(progressMsg)
            pref.saveSyncProgress(progressMsg)
        }
        return failedCount
    }

    private suspend fun fetchAlbumsBatch(batchIds: List<String>): List<Album?> = coroutineScope {
        val semaphore = Semaphore(ALBUM_FETCH_CONCURRENCY)
        batchIds.map { albumId ->
            async {
                semaphore.withPermit { fetchSingleAlbum(albumId) }
            }
        }.awaitAll()
    }

    private suspend fun fetchSingleAlbum(albumId: String): Album? {
        return try {
            val (u, t, s) = authManager.getAuthParams()
            val response = api.getAlbum(albumId, u, t, s)
            response.response.ensureOk()
            validateFetchedAlbum(albumId, response.response.album)
        } catch (e: Exception) {
            if (e is CancellationException || e.isSubsonicAuthError() || e is ServerUnavailableException) throw e
            LogManager.w("Id3SyncExecutor: getAlbum failed for id=$albumId: ${e.message}")
            null
        }
    }

    private fun validateFetchedAlbum(albumId: String, album: Album?): Album? {
        if (album == null) {
            LogManager.w("Id3SyncExecutor: Missing album payload for id=$albumId")
            return null
        }
        if (album.id != albumId) {
            LogManager.w("Id3SyncExecutor: Mismatched album payload: requested=$albumId, actual=${album.id}")
            return null
        }
        val completeness = MediaRepository.evaluateAlbumCompleteness(album.songCount, album.song)
        if (completeness.isKnownIncomplete) {
            LogManager.w(
                "Id3SyncExecutor: Incomplete album payload for id=$albumId " +
                    "(declared=${album.songCount}, actual=${album.song.size})"
            )
            return null
        }
        return album
    }

    private suspend fun persistAlbumsAndSongs(
        albums: List<Album>,
        seenSongIds: MutableSet<String>
    ) {
        val songs = albums.flatMap { album ->
            album.song.filter { seenSongIds.add(it.id) }.map { song -> album.id to song }
        }
        runInTransaction {
            musicDao.insertAlbums(albumListHelper.mergeAlbums(albums))
            for (batch in songs.chunked(BATCH_SIZE)) {
                val ids = batch.map { (_, song) -> song.id }
                val localById = musicDao.getSongsMetadataByIds(ids).associateBy { it.id }
                val entities = batch.map { (albumId, song) ->
                    entityMapper.mergeSyncSong(song, localById[song.id], fallbackAlbumId = albumId)
                }
                musicDao.insertSongs(entities)
                musicDao.insertSyncTempIds(ids.map { SyncTempIdEntity(it, "song") })
            }
        }
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val ALBUM_COMMIT_BATCH_SIZE = 32
        const val ALBUM_FETCH_CONCURRENCY = 8
        const val MAX_CONSECUTIVE_ALBUM_FAILURES = 16
    }
}
