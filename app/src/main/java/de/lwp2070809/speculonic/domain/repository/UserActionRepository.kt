package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.Starred2Response
import de.lwp2070809.speculonic.network.model.ensureOk
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
        skipInsert: Boolean = false
    ): List<Song> {
        return getStarredData(forceRefresh, skipInsert).song
    }

    suspend fun getStarredAlbums(
        forceRefresh: Boolean = false, 
        skipInsert: Boolean = false
    ): List<Album> {
        return getStarredData(forceRefresh, skipInsert).album
    }

    suspend fun refreshStarredData(
        forceRefresh: Boolean = false,
        skipInsert: Boolean = false
    ): Starred2Response {
        return getStarredData(forceRefresh, skipInsert)
    }

    private suspend fun getStarredData(
        forceRefresh: Boolean = false, 
        skipInsert: Boolean = false
    ): Starred2Response {
        val cachedSongs = musicDao.getStarredSongs()
        val cachedAlbums = musicDao.getStarredAlbums()
        
        val isCacheEmpty = cachedSongs.isEmpty() && cachedAlbums.isEmpty()
        val shouldFetchFromNetwork = forceRefresh || isCacheEmpty

        if (!shouldFetchFromNetwork) {
            return Starred2Response(
                song = cachedSongs.map { entityMapper.toSong(it) }, 
                album = cachedAlbums.map { entityMapper.toAlbum(it) }
            )
        }

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getStarred2(u, t, s)
            response.response.ensureOk()
            val starred2 = response.response.starred2 ?: Starred2Response()
            
            if (!skipInsert) {
                musicDao.clearAllSongStarredFlags()
                starred2.song.asSequence().chunked(500).forEach { chunk ->
                    val chunkIds = chunk.map { it.id }
                    val existingSongsMap = musicDao.getSongsByIds(chunkIds).associateBy { it.id }
                    val entities = chunk.map { song ->
                        val local = existingSongsMap[song.id]
                        entityMapper.songToEntity(
                            song = song, 
                            albumId = song.albumId ?: local?.albumId, 
                            artistId = song.artistId ?: local?.artistId,
                            localUri = local?.localUri, 
                            isCached = local?.isFullyCached ?: false,
                            isTranscoded = local?.isTranscoded ?: false,
                            parentId = song.parent ?: local?.parent,
                            isStarred = true,
                            lastUpdated = local?.lastUpdated
                        )
                    }
                    musicDao.insertSongs(entities)
                }
                
                syncStarredAlbums(starred2.album)
            }
            
            if (skipInsert) {
                return starred2
            }
            
            val finalSongs = musicDao.getStarredSongs().map { entityMapper.toSong(it) }
            val finalAlbums = musicDao.getStarredAlbums().map { entityMapper.toAlbum(it) }
            
            Starred2Response(song = finalSongs, album = finalAlbums)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (forceRefresh) throw e
            Starred2Response(
                song = cachedSongs.map { entityMapper.toSong(it) }, 
                album = cachedAlbums.map { entityMapper.toAlbum(it) }
            )
        }
    }

    private suspend fun syncStarredAlbums(albums: List<Album>) {
        if (albums.isEmpty()) {
            musicDao.syncStarredAlbums("starred", emptyList(), emptyList())
            return
        }
        val albumEntities = mutableListOf<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>()
        val listItems = mutableListOf<de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity>()
        val currentTime = System.currentTimeMillis()

        albums.chunked(500).forEachIndexed { chunkIndex, chunk ->
            val chunkIds = chunk.map { it.id }
            val existingAlbumsMap = musicDao.getAlbumsByIds(chunkIds).associateBy { it.id }
            
            chunk.forEachIndexed { index, album ->
                val existing = existingAlbumsMap[album.id]
                val entity = entityMapper.albumToEntity(album, existing = existing, isStarred = true)
                val updatedEntity = if (album.starred == null && (existing?.lastUpdated ?: 0L) > 0L) {
                    entity.copy(lastUpdated = currentTime)
                } else {
                    entity
                }
                albumEntities.add(updatedEntity)
                listItems.add(de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity("starred", album.id, chunkIndex * 500 + index))
            }
        }
        musicDao.syncStarredAlbums("starred", albumEntities, listItems)
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            musicDao.updateSongStarred(id, !star, System.currentTimeMillis())
            false
        }
    }

    suspend fun starAlbum(id: String, star: Boolean): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        val currentTime = System.currentTimeMillis()
        
        musicDao.setAlbumStarredStatus(id, star, currentTime)
        return try {
            val response = if (star) api.star(id, u, t, s) else api.unstar(id, u, t, s)
            val ok = response.response.status == "ok"
            if (!ok) {
                musicDao.setAlbumStarredStatus(id, !star, currentTime)
            }
            ok
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            musicDao.setAlbumStarredStatus(id, !star, currentTime)
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
