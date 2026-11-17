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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UserActionRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper
) {
    // 对同曲目的收藏/取消收藏请求按 songId 分片串行化，防止快速双击时慢请求反向覆盖最新状态
    private val songStarMutexes = Array(64) { Mutex() }

    private fun songStarMutex(id: String): Mutex {
        val index = (id.hashCode() and Int.MAX_VALUE) % songStarMutexes.size
        return songStarMutexes[index]
    }
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
                val songEntities = starred2.song.chunked(500).flatMap { chunk ->
                    val existingSongsMap = musicDao.getSongsByIds(chunk.map { it.id }).associateBy { it.id }
                    chunk.map { song ->
                        val local = existingSongsMap[song.id]
                        val lastUpdated = if (local?.starred != true) null else local.lastUpdated
                        entityMapper.songToEntity(
                            song = song,
                            albumId = song.albumId ?: local?.albumId,
                            artistId = song.artistId ?: local?.artistId,
                            localUri = local?.localUri,
                            isCached = local?.isFullyCached ?: false,
                            isTranscoded = local?.isTranscoded ?: false,
                            parentId = song.parent ?: local?.parent,
                            isStarred = true,
                            lastUpdated = lastUpdated
                        )
                    }
                }
                val (albumEntities, listItems) = buildStarredAlbumsPayload(starred2.album)
                musicDao.syncStarredData(songEntities, albumEntities, listItems)
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

    private suspend fun buildStarredAlbumsPayload(
        albums: List<Album>
    ): Pair<List<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>, List<de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity>> {
        if (albums.isEmpty()) {
            return emptyList<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>() to emptyList()
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
        return albumEntities to listItems
    }

    suspend fun starSong(id: String, star: Boolean): Boolean = songStarMutex(id).withLock {
        val (u, t, s) = authManager.getAuthParams()
        val existingSong = musicDao.getSongById(id)
        val rollbackStar = existingSong?.starred ?: !star

        musicDao.updateSongStarred(id, star, System.currentTimeMillis())
        return@withLock try {
            val response = if (star) api.star(id, u, t, s) else api.unstar(id, u, t, s)
            val ok = response.response.status == "ok"
            if (!ok) {
                musicDao.updateSongStarred(id, rollbackStar, System.currentTimeMillis())
            }
            ok
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            musicDao.updateSongStarred(id, rollbackStar, System.currentTimeMillis())
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
