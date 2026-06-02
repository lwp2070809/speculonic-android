package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.Starred2Response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class UserActionRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper
) {
    suspend fun getStarred(
        forceRefresh: Boolean = false, 
        hasLocalData: Boolean,
        skipInsert: Boolean = false
    ): List<Song> {
        return getStarredData(forceRefresh, hasLocalData, skipInsert).song
    }

    suspend fun getStarredAlbums(
        forceRefresh: Boolean = false, 
        hasLocalData: Boolean,
        skipInsert: Boolean = false
    ): List<Album> {
        return getStarredData(forceRefresh, hasLocalData, skipInsert).album
    }

    private suspend fun getStarredData(
        forceRefresh: Boolean = false, 
        hasLocalData: Boolean,
        skipInsert: Boolean = false
    ): Starred2Response {
        val cachedSongs = musicDao.getStarredSongs()
        val cachedAlbums = musicDao.getStarredAlbums()
        
        val shouldRefresh = forceRefresh || ((cachedSongs.isEmpty() && cachedAlbums.isEmpty()) && !hasLocalData)

        if (!shouldRefresh && (cachedSongs.isNotEmpty() || cachedAlbums.isNotEmpty())) {
            return Starred2Response(
                song = cachedSongs.map { entityMapper.toSong(it) }, 
                album = cachedAlbums.map { entityMapper.toAlbum(it) }
            )
        }
        
        if (!forceRefresh && hasLocalData) {
            return Starred2Response(
                song = cachedSongs.map { entityMapper.toSong(it) }, 
                album = cachedAlbums.map { entityMapper.toAlbum(it) }
            )
        }

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getStarred2(u, t, s)
            val starred2 = response.response.starred2 ?: Starred2Response()
            
            if (!skipInsert) {
                val songIds = starred2.song.map { it.id }
                val existingSongsMap = songIds.chunked(500)
                    .flatMap { musicDao.getSongsByIds(it) }
                    .associateBy { it.id }

                val entities = starred2.song.map { song ->
                    val local = existingSongsMap[song.id]
                    entityMapper.songToEntity(song, 
                        albumId = null, 
                        localUri = local?.localUri, 
                        isCached = local?.isFullyCached ?: false,
                        lastUpdated = local?.lastUpdated
                    )
                }
                musicDao.syncStarredSongs(entities)
                
                syncStarredAlbums(starred2.album)
            }
            
            if (skipInsert) {
                return starred2
            }
            
            val finalSongs = musicDao.getStarredSongs().map { entityMapper.toSong(it) }
            val finalAlbums = musicDao.getStarredAlbums().map { entityMapper.toAlbum(it) }
            
            Starred2Response(song = finalSongs, album = finalAlbums)
        } catch (e: Exception) {
            Starred2Response(
                song = cachedSongs.map { entityMapper.toSong(it) }, 
                album = cachedAlbums.map { entityMapper.toAlbum(it) }
            )
        }
    }

    private suspend fun syncStarredAlbums(albums: List<Album>) {
        val albumIds = albums.map { it.id }
        val existingAlbumsMap = if (albumIds.isNotEmpty()) {
            albumIds.chunked(500)
                .flatMap { musicDao.getAlbumsByIds(it) }
                .associateBy { it.id }
        } else emptyMap()
        
        val entities = albums.map { album ->
            entityMapper.albumToEntity(album, existing = existingAlbumsMap[album.id], isStarred = true)
        }
        val listItems = albums.mapIndexed { index, album -> 
            de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity("starred", album.id, index)
        }
        musicDao.syncStarredAlbums("starred", entities, listItems)
    }

    suspend fun starSong(id: String, star: Boolean): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        
        musicDao.updateSongStarred(id, star, System.currentTimeMillis())
        return try {
            val response = if (star) api.star(id, u, t, s) else api.unstar(id, u, t, s)
            val ok = response.response.status == "ok"
            if (!ok) {
                
                musicDao.updateSongStarred(id, !star, System.currentTimeMillis())
            }
            ok
        } catch (e: Exception) {
            
            musicDao.updateSongStarred(id, !star, System.currentTimeMillis())
            false
        }
    }

    suspend fun starAlbum(id: String, star: Boolean): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        
        musicDao.updateAlbumStarred(id, star, System.currentTimeMillis())
        return try {
            val response = if (star) api.star(id, u, t, s) else api.unstar(id, u, t, s)
            val ok = response.response.status == "ok"
            if (!ok) {
                
                musicDao.updateAlbumStarred(id, !star, System.currentTimeMillis())
            }
            ok
        } catch (e: Exception) {
            
            musicDao.updateAlbumStarred(id, !star, System.currentTimeMillis())
            false
        }
    }

    fun getStarredFlow(): Flow<List<Song>> {
        return musicDao.getStarredSongsFlow()
            .map { list -> list.map { entityMapper.toSong(it) } }
            .distinctUntilChanged()
    }

    fun getStarredAlbumsFlow(): Flow<List<Album>> {
        return musicDao.getStarredAlbumsFlow()
            .map { list -> list.map { entityMapper.toAlbum(it) } }
            .distinctUntilChanged()
    }
}
