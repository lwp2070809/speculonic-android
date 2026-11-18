package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.ensureOk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlaylistRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper
) {
    private val mutex = Mutex()
    suspend fun getPlaylists(
        forceRefresh: Boolean = false,
        hasLocalData: Boolean,
        skipInsert: Boolean = false
    ): List<Playlist> {
        val cached = musicDao.getPlaylists()
        val shouldRefresh = forceRefresh || (cached.isEmpty() && !hasLocalData)
        if (!shouldRefresh && cached.isNotEmpty()) {
            return cached.map { entityMapper.toPlaylist(it) }
        }

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getPlaylists(u, t, s)
            response.response.ensureOk()
            val list = response.response.playlists?.playlist ?: emptyList()
            if (skipInsert) {
                list.sortedBy { it.name }
            } else {
                persistSyncedPlaylists(list, cached)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (forceRefresh) throw e
            cached.map { entityMapper.toPlaylist(it) }
        }
    }

    private suspend fun persistSyncedPlaylists(
        list: List<Playlist>,
        cached: List<PlaylistEntity>
    ): List<Playlist> {
        if (list.isNotEmpty()) {
            val cachedMap = cached.associateBy { it.id }
            list.chunked(BATCH_CHUNK_SIZE).forEach { chunk ->
                musicDao.insertPlaylists(chunk.map { toPlaylistEntity(it, cachedMap[it.id]) })
            }
        }
        val serverIds = list.map { it.id }
        if (serverIds.isEmpty()) {
            musicDao.clearAllPlaylists()
        } else {
            musicDao.deletePlaylistsNotIn(serverIds)
        }
        return musicDao.getPlaylists().map { entityMapper.toPlaylist(it) }
    }

    private fun toPlaylistEntity(playlist: Playlist, local: PlaylistEntity?): PlaylistEntity {
        val serverCount = playlist.songCount ?: 0
        val finalCount = if (serverCount == 0 && local != null && local.songCount > 0) {
            local.songCount
        } else {
            serverCount
        }
        return PlaylistEntity(
            id = playlist.id,
            name = playlist.name,
            comment = playlist.comment,
            owner = playlist.owner,
            `public` = playlist.public ?: false,
            songCount = finalCount,
            duration = playlist.duration?.toLong() ?: 0L,
            coverArt = playlist.coverArt,
            pinned = local?.pinned ?: false
        )
    }

    suspend fun getPlaylist(
        id: String, 
        forceRefresh: Boolean = false,
        skipInsert: Boolean = false
    ): List<Song> {
        val cached = musicDao.getSongsByPlaylist(id)
        val shouldRefresh = forceRefresh || cached.isEmpty()

        if (!shouldRefresh) {
            return cached.map { entityMapper.toSong(it) }
        }

        return try {
            val playlistInfo = fetchRemotePlaylist(id, forceRefresh)
            val finalPlaylistEntity = buildPlaylistEntity(id, playlistInfo)
            val entities = buildPlaylistSongEntities(playlistInfo.entry)
            val refs = playlistInfo.entry.mapIndexed { index, song ->
                PlaylistSongCrossRef(playlistId = id, songId = song.id, order = index)
            }

            if (!skipInsert) {
                musicDao.syncPlaylistWithSongs(
                    playlist = finalPlaylistEntity,
                    songs = entities,
                    refs = refs
                )
            }
            entities.map { entityMapper.toSong(it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is PlaylistNotFoundException) {
                de.lwp2070809.speculonic.util.LogManager.w("PlaylistRepository: playlist id=$id not found on server (cleaned locally)")
            } else {
                de.lwp2070809.speculonic.util.LogManager.e("PlaylistRepository: getPlaylist failed for id=$id", e)
            }
            throw e
        }
    }

    private suspend fun fetchRemotePlaylist(
        id: String,
        forceRefresh: Boolean
    ): de.lwp2070809.speculonic.network.model.Playlist {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        val response = api.getPlaylist(id, u, t, s)
        if (response.response.status != "ok") {
            val err = response.response.error
            if (err?.code == SUBSONIC_ERROR_NOT_FOUND) {
                musicDao.deletePlaylistWithSongs(id)
                throw PlaylistNotFoundException(id, err.message)
            }
            error(err?.message ?: "Subsonic server returned status: ${response.response.status}")
        }
        return response.response.playlist ?: error("Playlist data is missing in server response")
    }

    private suspend fun buildPlaylistEntity(
        id: String,
        playlistInfo: de.lwp2070809.speculonic.network.model.Playlist
    ): PlaylistEntity {
        val currentPlaylist = musicDao.getPlaylistById(id)
        return if (currentPlaylist != null) {
            currentPlaylist.copy(
                name = playlistInfo.name,
                comment = playlistInfo.comment,
                owner = playlistInfo.owner,
                `public` = playlistInfo.public ?: false,
                songCount = playlistInfo.entry.size,
                duration = playlistInfo.duration?.toLong() ?: 0L,
                coverArt = playlistInfo.coverArt,
                lastUpdated = System.currentTimeMillis()
            )
        } else {
            PlaylistEntity(
                id = playlistInfo.id,
                name = playlistInfo.name,
                comment = playlistInfo.comment,
                owner = playlistInfo.owner,
                `public` = playlistInfo.public ?: false,
                songCount = playlistInfo.entry.size,
                duration = playlistInfo.duration?.toLong() ?: 0L,
                coverArt = playlistInfo.coverArt,
                pinned = false,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    private suspend fun buildPlaylistSongEntities(list: List<Song>): List<de.lwp2070809.speculonic.data.db.entities.SongEntity> {
        val songIds = list.map { it.id }
        val existingSongsMap = if (songIds.isNotEmpty()) {
            songIds.chunked(BATCH_CHUNK_SIZE)
                .flatMap { musicDao.getSongsByIds(it) }
                .associateBy { it.id }
        } else {
            emptyMap()
        }

        return list.map { song ->
            val local = existingSongsMap[song.id]
            val isStarred = song.starred != null
            val lastUpdated = if (isStarred && local?.starred != true) null else local?.lastUpdated
            entityMapper.songToEntity(
                song = song,
                overrides = SongEntityOverrides(
                    albumId = song.albumId ?: local?.albumId,
                    artistId = song.artistId ?: local?.artistId,
                    parentId = song.parent ?: local?.parent,
                    localUri = local?.localUri,
                    isCached = local?.isFullyCached ?: false,
                    isTranscoded = local?.isTranscoded ?: false,
                    isStarred = isStarred,
                    lastUpdated = lastUpdated
                )
            )
        }
    }

    fun getPlaylistsFlow(): Flow<List<Playlist>> {
        return musicDao.getPlaylistsFlow().map { list -> list.map { entityMapper.toPlaylist(it) } }
    }

    fun getPinnedPlaylistsFlow(): Flow<List<Playlist>> {
        return musicDao.getPinnedPlaylistsFlow().map { list -> list.map { entityMapper.toPlaylist(it) } }
    }

    fun getPlaylistByIdFlow(id: String): Flow<Playlist?> {
        return musicDao.getPlaylistByIdFlow(id).map { it?.let { entityMapper.toPlaylist(it) } }
    }

    suspend fun getCachedPlaylist(id: String): Playlist? {
        return musicDao.getPlaylistById(id)?.let { entityMapper.toPlaylist(it) }
    }

    suspend fun getCachedPlaylistSongs(id: String): List<Song> {
        return musicDao.getSongsByPlaylist(id).map { entityMapper.toSong(it) }
    }

    suspend fun togglePlaylistPinned(playlistId: String, pinned: Boolean) {
        musicDao.updatePlaylistPinned(playlistId, pinned)
    }

    suspend fun createPlaylist(name: String, songIds: List<String>? = null, hasLocalData: Boolean): Boolean {
        val safeName = name.trim().take(100).replace(Regex("[\\p{Cntrl}]"), "")
        if (safeName.isEmpty()) return false
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.createPlaylist(safeName, songIds, u, t, s)
            val success = response.response.status == "ok"
            if (success) {
                val playlist = response.response.playlist
                if (playlist != null) {
                    val entity = de.lwp2070809.speculonic.data.db.entities.PlaylistEntity(
                        id = playlist.id,
                        name = playlist.name,
                        comment = playlist.comment,
                        owner = playlist.owner,
                        `public` = playlist.public ?: false,
                        songCount = playlist.songCount ?: 0,
                        duration = playlist.duration?.toLong() ?: 0L,
                        coverArt = playlist.coverArt,
                        pinned = false
                    )
                    musicDao.insertPlaylists(listOf(entity))
                } else {
                    getPlaylists(forceRefresh = true, hasLocalData = hasLocalData)
                }
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun deletePlaylist(id: String): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.deletePlaylist(id, u, t, s)
            val success = response.response.status == "ok"
            if (success) {
                musicDao.deletePlaylistWithSongs(id)
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun addToPlaylist(playlistId: String, songId: String, hasLocalData: Boolean): Boolean = mutex.withLock {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.updatePlaylist(playlistId, u, t, s, songIdToAdd = songId)
            val success = response.response.status == "ok"
            if (success) {
                getPlaylist(playlistId, forceRefresh = true)
                getPlaylists(forceRefresh = true, hasLocalData = hasLocalData)
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int, hasLocalData: Boolean): Boolean = mutex.withLock {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.updatePlaylist(playlistId, u, t, s, songIndexToRemove = index)
            val success = response.response.status == "ok"
            if (success) {
                getPlaylist(playlistId, forceRefresh = true)
                getPlaylists(forceRefresh = true, hasLocalData = hasLocalData)
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    private companion object {
        const val SUBSONIC_ERROR_NOT_FOUND = 70
        const val BATCH_CHUNK_SIZE = 500
    }
}
