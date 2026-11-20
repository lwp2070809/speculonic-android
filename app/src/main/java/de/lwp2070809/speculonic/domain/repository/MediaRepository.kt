package de.lwp2070809.speculonic.domain.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import androidx.room.withTransaction
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.SearchResult3
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.ensureOk
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MediaRepository(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper,
    private val database: AppDatabase
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
            fetchAndPersistArtistDetails(id, forceRefresh, skipInsert, albums)?.let { return it }
        }

        val cachedArtist = artistEntity?.let {
            Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount)
        }
        return Pair(cachedArtist, albums)
    }

    private suspend fun fetchAndPersistArtistDetails(
        id: String,
        forceRefresh: Boolean,
        skipInsert: Boolean,
        fallbackAlbums: List<Album>
    ): Pair<Artist?, List<Album>>? {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = forceRefresh)
        return try {
            val response = api.getArtist(id, u, t, s)
            response.response.ensureOk()
            val artist = response.response.artist ?: return null
            val finalAlbums = artist.album.ifEmpty { fallbackAlbums }
            if (!skipInsert) {
                persistArtistAndAlbums(artist, finalAlbums.size)
            }
            Pair(artist, finalAlbums)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogManager.e("MediaRepository: getArtist failed", e)
            if (forceRefresh) throw e
            null
        }
    }

    private suspend fun persistArtistAndAlbums(artist: Artist, resolvedAlbumCount: Int) {
        musicDao.insertArtists(
            listOf(
                de.lwp2070809.speculonic.data.db.entities.ArtistEntity(
                    artist.id,
                    artist.name,
                    artist.coverArt,
                    artist.albumCount ?: resolvedAlbumCount
                )
            )
        )
        if (artist.album.isNotEmpty()) {
            val albumEntities = artist.album.map { entityMapper.albumToEntity(it, isStarred = it.starred != null) }
            musicDao.insertAlbums(albumEntities)
        }
    }

    suspend fun getCachedArtistDetails(id: String): Pair<Artist?, List<Album>> {
        val artistEntity = musicDao.getArtistById(id)
        val albums = musicDao.getAlbumsByArtist(id).map { entityMapper.toAlbum(it) }
        return Pair(artistEntity?.let { Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount) }, albums)
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
        if (forceRefresh) {
            return refreshAlbumList(type, size, offset, skipInsert = skipInsert)
        }
        val cached = musicDao.getAlbumsByListType(type)
        if (cached.size > offset) {
            return cached.drop(offset).take(size).map { entityMapper.toAlbum(it) }
        }
        if (offset > 0 || !hasLocalData || type == "random") {
            return refreshAlbumList(type, size, offset, skipInsert = skipInsert)
        }
        return emptyList()
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
            response.response.ensureOk()
            val list = response.response.albumList2?.album ?: emptyList()
            if (list.isNotEmpty() && !skipInsert) {
                saveAlbumListChunk(type, list, offset)
            }
            return list
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogManager.e("MediaRepository: refreshAlbumList failed for $type", e)
            throw e
        }
    }

    suspend fun getAlbumsWithoutSongs(albumIds: List<String>): List<String> {
        return musicDao.getAlbumsWithoutSongs(albumIds)
    }

    private suspend fun saveAlbumListChunk(listType: String, albums: List<Album>, offset: Int) {
        if (albums.isEmpty()) return
        val albumIds = albums.map { it.id }
        val existingAlbumsMap = albumIds.chunked(500)
            .flatMap { musicDao.getAlbumsByIds(it) }
            .associateBy { it.id }
        
        val entities = albums.map { album ->
            val existing = existingAlbumsMap[album.id]
            entityMapper.albumToEntity(album, existing = existing, isStarred = album.starred != null)
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
            response.response.ensureOk()
            val album = response.response.album
            if (album != null) {
                check(album.id == id) { "Mismatched album response for $id" }
                val hasCompleteSongs = (album.songCount ?: 0) <= album.song.distinctBy { it.id }.size
                check(!(forceRefresh && cachedSongs.isNotEmpty() && !hasCompleteSongs)) {
                    "Incomplete album response for $id"
                }
                if (!skipInsert) {
                    database.withTransaction {
                        persistFetchedAlbum(
                            albumId = id,
                            album = album,
                            existingAlbum = musicDao.getAlbumById(id),
                            detachRemoved = forceRefresh && hasCompleteSongs
                        )
                    }
                }
            }
            album
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (forceRefresh) throw e
            albumEntity?.let { existing ->
                entityMapper.toAlbum(existing, cachedSongs.map { entityMapper.toSong(it) })
            }
        }
    }

    private suspend fun persistFetchedAlbum(
        albumId: String,
        album: Album,
        existingAlbum: de.lwp2070809.speculonic.data.db.entities.AlbumEntity?,
        detachRemoved: Boolean
    ) {
        musicDao.insertAlbums(
            listOf(entityMapper.albumToEntity(album, existing = existingAlbum, isStarred = album.starred != null))
        )
        val songIds = album.song.map { it.id }
        val existingSongsMap = songIds.chunked(500)
            .flatMap { musicDao.getSongsByIds(it) }
            .associateBy { it.id }

        val entities = album.song.map { song ->
            val local = existingSongsMap[song.id]
            val songIsStarred = song.starred != null || local?.starred == true
            val lastUpdated = if (songIsStarred && local?.starred != true) null else local?.lastUpdated
            entityMapper.songToEntity(
                song = song,
                overrides = SongEntityOverrides(
                    albumId = albumId,
                    parentId = song.parent ?: albumId,
                    localUri = local?.localUri,
                    isCached = local?.isFullyCached ?: false,
                    isTranscoded = local?.isTranscoded ?: false,
                    isStarred = songIsStarred,
                    lastUpdated = lastUpdated
                )
            )
        }
        if (detachRemoved) {
            val removedIds = musicDao.getSongIdsByAlbum(albumId) - songIds.toHashSet()
            removedIds.chunked(500).forEach { musicDao.detachAlbumSongs(albumId, it) }
        }
        entities.chunked(500).forEach { chunk -> musicDao.insertSongs(chunk) }
    }

    suspend fun getCachedAlbum(id: String): Album? {
        val albumEntity = musicDao.getAlbumById(id) ?: return null
        val cachedSongs = musicDao.getSongsByAlbum(id)
        return entityMapper.toAlbum(albumEntity, cachedSongs.map { entityMapper.toSong(it) })
    }

    suspend fun refreshArtists() {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
        try {
            val response = api.getIndexes(u, t, s)
            val indexes = response.response.indexes ?: return
            val allArtists = indexes.index.flatMap { it.artist }
            if (allArtists.isNotEmpty()) {
                allArtists.chunked(500).forEach { chunk ->
                    musicDao.insertArtists(
                        chunk.map {
                            de.lwp2070809.speculonic.data.db.entities.ArtistEntity(
                                id = it.id,
                                name = it.name,
                                coverArt = it.coverArt,
                                albumCount = it.albumCount ?: 0
                            )
                        }
                    )
                }
                musicDao.repairAndCount()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            response.response.ensureOk()
            val results = response.response.searchResult3 ?: SearchResult3()
            val songIds = results.song.map { it.id }.distinct()
            val existingMap = songIds.chunked(500)
                .flatMap { musicDao.getSearchSongMetadata(it) }
                .associateBy { it.id }
            results.copy(song = results.song.map { song ->
                val local = existingMap[song.id]
                song.copy(
                    localUri = local?.localUri,
                    isFullyCached = local?.isFullyCached ?: false,
                    isTranscoded = local?.isTranscoded ?: false
                )
            })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("MediaRepository: search failed for query '$query'", e)
            throw e
        }
    }

    suspend fun searchLocal(query: String): SearchResult3 {
        val normalized = query.trim()
        if (normalized.isEmpty()) return SearchResult3()
        val escaped = escapeSqlLike(normalized)
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val artistsDeferred = async {
                    musicDao.searchArtistsLocal(escaped, SEARCH_ARTIST_LIMIT).map {
                        Artist(id = it.id, name = it.name, coverArt = it.coverArt, albumCount = it.albumCount)
                    }
                }
                val albumsDeferred = async {
                    musicDao.searchAlbumsLocal(escaped, SEARCH_ALBUM_LIMIT).map { entityMapper.toAlbum(it) }
                }
                val songsDeferred = async {
                    musicDao.searchSongsLocal(escaped, SEARCH_SONG_LIMIT).map { entityMapper.toSong(it) }
                }
                SearchResult3(
                    artist = artistsDeferred.await(),
                    album = albumsDeferred.await(),
                    song = songsDeferred.await()
                )
            }
        }
    }

    private fun escapeSqlLike(query: String): String {
        return query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    private companion object {
        const val SEARCH_ARTIST_LIMIT = 20
        const val SEARCH_ALBUM_LIMIT = 20
        const val SEARCH_SONG_LIMIT = 100
    }
}
