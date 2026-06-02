package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
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
            val list = response.response.playlists?.playlist ?: emptyList()
            if (!skipInsert) {
                if (list.isNotEmpty()) {
                    val cachedMap = cached.associateBy { it.id }
                    list.chunked(500).forEach { chunk ->
                        musicDao.insertPlaylists(chunk.map { playlist ->
                            val local = cachedMap[playlist.id]
                            val serverCount = playlist.songCount ?: 0
                            val finalCount = if (serverCount == 0 && local != null && local.songCount > 0) {
                                local.songCount
                            } else {
                                serverCount
                            }
                            PlaylistEntity(
                                id = playlist.id,
                                name = playlist.name,
                                comment = playlist.comment,
                                owner = playlist.owner,
                                songCount = finalCount,
                                duration = playlist.duration ?: 0,
                                coverArt = playlist.coverArt,
                                pinned = local?.pinned ?: false
                            )
                        })
                    }
                }
                
                val serverIds = list.map { it.id }
                if (serverIds.isEmpty()) {
                    musicDao.clearAllPlaylists()
                } else {
                    musicDao.deletePlaylistsNotIn(serverIds)
                }
            }
            list
        } catch (e: Exception) {
            cached.map { entityMapper.toPlaylist(it) }
        }
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

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getPlaylist(id, u, t, s)
            val list = response.response.playlist?.entry ?: emptyList()
            if (list.isNotEmpty() && !skipInsert) {
                val songIds = list.map { it.id }
                val existingSongsMap = songIds.chunked(500)
                    .flatMap { musicDao.getSongsByIds(it) }
                    .associateBy { it.id }

                val entities = list.map { song ->
                    val local = existingSongsMap[song.id]
                    entityMapper.songToEntity(song, 
                        albumId = null, 
                        localUri = local?.localUri, 
                        isCached = local?.isFullyCached ?: false,
                        lastUpdated = local?.lastUpdated
                    )
                }
                val refs = list.mapIndexed { index, song -> PlaylistSongCrossRef(playlistId = id, songId = song.id, order = index) }
                
                musicDao.deletePlaylistSongs(id)
                entities.chunked(500).forEach { musicDao.insertSongs(it) }
                refs.chunked(500).forEach { musicDao.insertPlaylistSongCrossRefs(it) }

                
                
                val currentPlaylist = musicDao.getPlaylistById(id)
                if (currentPlaylist != null) {
                    musicDao.insertPlaylists(listOf(currentPlaylist.copy(songCount = list.size)))
                }
            } else if (response.response.status == "ok" && !skipInsert) {
                musicDao.deletePlaylistSongs(id)
            }
            list
        } catch (e: Exception) {
            cached.map { entityMapper.toSong(it) }
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

    suspend fun togglePlaylistPinned(playlistId: String, pinned: Boolean) {
        musicDao.updatePlaylistPinned(playlistId, pinned)
    }

    suspend fun createPlaylist(name: String, songIds: List<String>? = null, hasLocalData: Boolean): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > 255) return false
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.createPlaylist(trimmedName, songIds, u, t, s)
            val success = response.response.status == "ok"
            if (success) {
                getPlaylists(forceRefresh = true, hasLocalData = hasLocalData)
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deletePlaylist(id: String): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.deletePlaylist(id, u, t, s)
            val success = response.response.status == "ok"
            if (success) {
                musicDao.deletePlaylist(id)
                musicDao.deletePlaylistSongs(id)
            }
            success
        } catch (e: Exception) {
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
            false
        }
    }
}
