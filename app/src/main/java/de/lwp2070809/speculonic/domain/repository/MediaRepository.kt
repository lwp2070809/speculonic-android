package de.lwp2070809.speculonic.domain.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.ArtistInfo2
import de.lwp2070809.speculonic.network.model.SearchResult3
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MediaRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper
) {
    fun getArtistsFlow(): Flow<List<Artist>> {
        return musicDao.getAllArtistsFlow().map { list -> 
            list.map { Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount) }
        }.distinctUntilChanged()
    }

    suspend fun getArtistDetails(
        id: String, 
        forceRefresh: Boolean = false,
        skipInsert: Boolean = false
    ): Pair<Artist?, List<Album>> {
        val artistEntity = musicDao.getArtistById(id)
        val albums = musicDao.getAlbumsByArtist(id).map { entityMapper.toAlbum(it) }
        
        if (forceRefresh || (artistEntity == null && albums.isEmpty())) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
            try {
                val response = api.getArtist(id, u, t, s)
                val artist = response.response.artist
                if (artist != null) {
                    if (!skipInsert) {
                        musicDao.insertArtists(listOf(de.lwp2070809.speculonic.data.db.entities.ArtistEntity(artist.id, artist.name, artist.coverArt, artist.albumCount ?: artist.album.size)))
                        if (artist.album.isNotEmpty()) {
                            val albumEntities = artist.album.map { entityMapper.albumToEntity(it, isStarred = it.starred != null) }
                            musicDao.insertAlbums(albumEntities)
                        }
                    }
                    return Pair(artist, artist.album)
                }
            } catch (e: Exception) {
                LogManager.e("MediaRepository: getArtist failed", e)
            }
        }
        
        return Pair(artistEntity?.let { Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount) }, albums)
    }

    suspend fun getArtistInfo(id: String): ArtistInfo2? {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.getArtistInfo2(id, u, t, s)
            response.response.artistInfo2
        } catch (e: Exception) {
            LogManager.e("MediaRepository: getArtistInfo2 failed", e)
            null
        }
    }

    fun getAlbumsByListTypeFlow(type: String): Flow<List<Album>> {
        return musicDao.getAlbumsByListTypeFlow(type).map { list ->
            list.map { entityMapper.toAlbum(it) }
        }.distinctUntilChanged()
    }

    suspend fun getAlbumList(
        type: String = "newest", 
        size: Int = 20, 
        offset: Int = 0, 
        forceRefresh: Boolean = false, 
        hasLocalData: Boolean,
        skipInsert: Boolean = false
    ): List<Album> {
        if (!forceRefresh) {
            val cached = musicDao.getAlbumsByListType(type)
            if (cached.isNotEmpty()) {
                if (cached.size > offset) {
                    return cached.drop(offset).take(size).map { entityMapper.toAlbum(it) }
                }
            }
            if (hasLocalData && type != "random") {
                return emptyList()
            }
        }

        
        if (forceRefresh) {
            return refreshAlbumList(type, size, offset, skipInsert = skipInsert)
        }
        
        return musicDao.getAlbumsByListType(type).drop(offset).take(size).map { entityMapper.toAlbum(it) }
    }

    suspend fun refreshAlbumList(
        type: String = "newest", 
        size: Int = 20, 
        offset: Int = 0,
        skipInsert: Boolean = false
    ): List<Album> {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
        try {
            val response = api.getAlbumList2(u, t, s, type = type, size = size, offset = offset)
            val list = response.response.albumList2?.album ?: emptyList()
            if (list.isNotEmpty() && !skipInsert) {
                saveAlbumListChunk(type, list, offset)
            }
            return list
        } catch (e: Exception) {
            LogManager.e("MediaRepository: refreshAlbumList failed for $type", e)
            return emptyList()
        }
    }

    private suspend fun saveAlbumListChunk(listType: String, albums: List<Album>, offset: Int) {
        if (albums.isEmpty()) return
        val albumIds = albums.map { it.id }
        val existingAlbumsMap = albumIds.chunked(500)
            .flatMap { musicDao.getAlbumsByIds(it) }
            .associateBy { it.id }
        
        val entities = albums.map { album ->
            val existing = existingAlbumsMap[album.id]
            entityMapper.albumToEntity(album, existing = existing, isStarred = album.starred != null || (existing?.starred ?: false))
        }
        val listItems = albums.mapIndexed { index, album -> 
            de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity(listType, album.id, index + offset)
        }
        
        if (offset == 0) {
            musicDao.updateAlbumList(listType, entities, listItems)
        } else {
            entities.chunked(500).forEach { musicDao.insertAlbums(it) }
            listItems.chunked(500).forEach { musicDao.insertAlbumListItems(it) }
        }
    }

    suspend fun getSongs(directoryId: String, forceRefresh: Boolean = false): List<Song> {
        val cached = musicDao.getSongsByParent(directoryId)
        val shouldRefresh = forceRefresh || cached.isEmpty()

        if (!shouldRefresh) {
            return cached.map { entityMapper.toSong(it) }
        }

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getMusicDirectory(directoryId, u, t, s)
            val list = response.response.directory?.child ?: emptyList()
            if (list.isNotEmpty()) {
                val songIds = list.map { it.id }
                val existingSongsMap = songIds.chunked(500)
                    .flatMap { musicDao.getSongsByIds(it) }
                    .associateBy { it.id }
                
                val entities = list.map { song ->
                    val local = existingSongsMap[song.id]
                    entityMapper.songToEntity(song, 
                        albumId = song.albumId ?: directoryId, 
                        localUri = local?.localUri, 
                        isCached = local?.isFullyCached ?: false,
                        parentId = directoryId,
                        lastUpdated = local?.lastUpdated
                    )
                }
                
                if (forceRefresh) {
                    musicDao.deleteSongsByParentNotIn(directoryId, songIds)
                }
                entities.chunked(500).forEach { chunk -> musicDao.insertSongs(chunk) }
            } else if (forceRefresh) {
                musicDao.deleteSongsByParentNotIn(directoryId, emptyList())
            }
            list
        } catch (e: Exception) {
            cached.map { entityMapper.toSong(it) }
        }
    }

    suspend fun getAlbum(
        id: String, 
        forceRefresh: Boolean = false,
        skipInsert: Boolean = false
    ): Album? {
        val cachedSongs = musicDao.getSongsByAlbum(id)
        val albumEntity = musicDao.getAlbumById(id)
        
        val shouldRefresh = forceRefresh || cachedSongs.isEmpty() || albumEntity == null
        
        if (!shouldRefresh) {
            return entityMapper.toAlbum(albumEntity, cachedSongs.map { entityMapper.toSong(it) })
        }

        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getAlbum(id, u, t, s)
            val album = response.response.album
            if (album != null) {
                if (!skipInsert) {
                    val isStarred = if (album.starred != null) true else (albumEntity?.starred ?: false)
                    musicDao.insertAlbums(listOf(entityMapper.albumToEntity(album, existing = albumEntity, isStarred = isStarred)))
                    
                    val songIds = album.song.map { it.id }
                    val existingSongsMap = songIds.chunked(500)
                        .flatMap { musicDao.getSongsByIds(it) }
                        .associateBy { it.id }
                    
                    val entities = album.song.map { song ->
                        val local = existingSongsMap[song.id]
                        entityMapper.songToEntity(song, 
                            albumId = id, 
                            localUri = local?.localUri, 
                            isCached = local?.isFullyCached ?: false,
                            parentId = song.parent ?: id,
                            lastUpdated = local?.lastUpdated
                        )
                    }
                    
                    if (forceRefresh) {
                        musicDao.deleteSongsByAlbumNotIn(id, songIds)
                    }
                    entities.chunked(500).forEach { chunk -> musicDao.insertSongs(chunk) }
                }
                return album
            } else if (forceRefresh && !skipInsert) {
                musicDao.deleteSongsByAlbumNotIn(id, emptyList())
            }
            album
        } catch (e: Exception) {
            albumEntity?.let { album -> entityMapper.toAlbum(album, cachedSongs.map { song -> entityMapper.toSong(song) }) }
        }
    }

    suspend fun refreshArtists() {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
        try {
            val response = api.getIndexes(u, t, s)
            val indexes = response.response.indexes ?: return
            val allArtists = indexes.index.flatMap { it.artist }
            if (allArtists.isNotEmpty()) {
                allArtists.chunked(500).forEach { chunk ->
                    musicDao.insertArtists(chunk.map { de.lwp2070809.speculonic.data.db.entities.ArtistEntity(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount ?: 0) })
                }
                musicDao.repairAndCount()
            }
        } catch (e: Exception) {
            LogManager.e("MediaRepository: refreshArtists failed", e)
            throw e
        }
    }



    fun getAllAlbumsFlow(): Flow<List<Album>> {
        return musicDao.getAllAlbumsFlow().map { list -> list.map { entityMapper.toAlbum(it) } }.distinctUntilChanged()
    }

    fun getSongsByAlbumFlow(albumId: String): Flow<List<Song>> {
        return musicDao.getSongsByAlbumFlow(albumId).map { list -> list.map { entityMapper.toSong(it) } }.distinctUntilChanged()
    }

    fun getSongsByPlaylistFlow(playlistId: String): Flow<List<Song>> {
        return musicDao.getSongsByPlaylistFlow(playlistId).map { list -> list.map { entityMapper.toSong(it) } }.distinctUntilChanged()
    }

    fun getSongByIdFlow(songId: String): Flow<Song?> {
        return musicDao.getSongByIdFlow(songId).map { it?.let { entityMapper.toSong(it) } }.distinctUntilChanged()
    }

    fun getAlbumByIdFlow(albumId: String): Flow<Album?> {
        return musicDao.getAlbumByIdFlow(albumId).map { it?.let { entityMapper.toAlbum(it) } }.distinctUntilChanged()
    }

    fun getAllSongsPaged(): Flow<PagingData<Song>> {
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false)
        ) {
            musicDao.getAllSongsPagingSource()
        }.flow.map { pagingData ->
            pagingData.map { entityMapper.toSong(it) }
        }
    }

    fun getAllSongsFlow(): Flow<List<Song>> {
        return musicDao.getAllSongsFlow().map { list -> list.map { entityMapper.toSong(it) } }.distinctUntilChanged()
    }

    suspend fun search(query: String): SearchResult3 {
        val (u, t, s) = authManager.getAuthParams()
        return try {
            val response = api.search3(query, u = u, t = t, s = s)
            val results = response.response.searchResult3 ?: SearchResult3()
            val songIds = results.song.map { it.id }
            val existingMap = songIds.chunked(500)
                .flatMap { musicDao.getSongsByIds(it) }
                .associateBy { it.id }
            results.copy(song = results.song.map { song ->
                val local = existingMap[song.id]
                song.copy(localUri = local?.localUri, isFullyCached = local?.isFullyCached ?: false)
            })
        } catch (e: Exception) {
            SearchResult3()
        }
    }
}
