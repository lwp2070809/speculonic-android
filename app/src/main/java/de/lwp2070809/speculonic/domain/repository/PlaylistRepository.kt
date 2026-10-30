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
                                `public` = playlist.public ?: false,
                                songCount = finalCount,
                                duration = playlist.duration?.toLong() ?: 0L,
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
                musicDao.getPlaylists().map { entityMapper.toPlaylist(it) }
            } else {
                list.sortedBy { it.name }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (forceRefresh) throw e
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
            if (response.response.status != "ok") {
                val error = response.response.error
                if (error?.code == 70) {
                    musicDao.deletePlaylistWithSongs(id)
                    throw PlaylistNotFoundException(id, error.message)
                }
                val errorMsg = error?.message ?: "Subsonic server returned status: ${response.response.status}"
                throw IllegalStateException(errorMsg)
            }
            val playlistInfo = response.response.playlist
                ?: throw IllegalStateException("Playlist data is missing in server response")
            val list = playlistInfo.entry

            val currentPlaylist = musicDao.getPlaylistById(id)
            val songIds = list.map { it.id }
            val existingSongsMap = if (songIds.isNotEmpty()) {
                songIds.chunked(500)
                    .flatMap { musicDao.getSongsByIds(it) }
                    .associateBy { it.id }
            } else {
                emptyMap()
            }

            val entities = list.map { song ->
                val local = existingSongsMap[song.id]
                entityMapper.songToEntity(
                    song = song, 
                    albumId = song.albumId ?: local?.albumId, 
                    artistId = song.artistId ?: local?.artistId,
                    localUri = local?.localUri, 
                    isCached = local?.isFullyCached ?: false,
                    isTranscoded = local?.isTranscoded ?: false,
                    parentId = song.parent ?: local?.parent,
                    isStarred = if (song.starred != null) true else local?.starred,
                    lastUpdated = local?.lastUpdated
                )
            }
            val refs = list.mapIndexed { index, song -> 
                PlaylistSongCrossRef(playlistId = id, songId = song.id, order = index) 
            }

            val finalPlaylistEntity = if (currentPlaylist != null) {
                currentPlaylist.copy(
                    name = playlistInfo.name,
                    comment = playlistInfo.comment,
                    owner = playlistInfo.owner,
                    `public` = playlistInfo.public ?: false,
                    songCount = list.size,
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
                    songCount = list.size,
                    duration = playlistInfo.duration?.toLong() ?: 0L,
                    coverArt = playlistInfo.coverArt,
                    pinned = false,
                    lastUpdated = System.currentTimeMillis()
                )
            }

            if (!skipInsert) {
                musicDao.syncPlaylistWithSongs(
                    playlist = finalPlaylistEntity,
                    songs = entities,
                    refs = refs
                )
            }

            if (entities.isNotEmpty()) {
                entities.map { entityMapper.toSong(it) }
            } else {
                emptyList()
            }
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
}
