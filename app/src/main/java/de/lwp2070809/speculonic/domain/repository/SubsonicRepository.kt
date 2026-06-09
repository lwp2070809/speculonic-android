package de.lwp2070809.speculonic.domain.repository

import android.content.Context
import coil3.request.ImageRequest
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.ArtistInfo2
import de.lwp2070809.speculonic.network.model.BaseResponse
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.SearchResult3
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class SubsonicRepository(
    private val context: Context,
    private val musicDao: MusicDao,
    private val preferencesManager: PreferencesManager,
    private val okHttpClient: OkHttpClient
) {
    private var repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val entityMapper = EntityMapper
    
    private data class Components(
        val baseUrl: String,
        val api: SubsonicService,
        val authManager: AuthManager,
        val urlBuilder: UrlBuilder,
        val syncManager: SyncManager,
        val playlistRepository: PlaylistRepository,
        val userActionRepository: UserActionRepository,
        val lyricsRepository: LyricsRepository,
        val mediaRepository: MediaRepository,
        val coverArtSyncManager: CoverArtSyncManager
    )

    @Volatile private var components: Components? = null
    
    private val baseUrl: String get() = components?.baseUrl ?: ""
    private val api: SubsonicService get() = components!!.api
    private val authManager: AuthManager get() = components!!.authManager
    private val urlBuilder: UrlBuilder get() = components!!.urlBuilder
    private val syncManager: SyncManager get() = components!!.syncManager
    private val playlistRepository: PlaylistRepository get() = components!!.playlistRepository
    private val userActionRepository: UserActionRepository get() = components!!.userActionRepository
    private val lyricsRepository: LyricsRepository get() = components!!.lyricsRepository
    private val mediaRepository: MediaRepository get() = components!!.mediaRepository
    private val coverArtSyncManager: CoverArtSyncManager get() = components!!.coverArtSyncManager

    private var serverCapabilities = ServerCapabilities()

    init {
        
        val initialUrl = preferencesManager.getServerUrlSync()
        val initialUser = preferencesManager.getUsernameSync()
        val initialPass = preferencesManager.getPasswordSync()
        
        val cachedCaps = preferencesManager.getServerCapabilitiesSync()
        if (cachedCaps != null) {
            serverCapabilities = cachedCaps
        }
        
        reconfigure(initialUrl, initialUser, initialPass)
        
        repositoryScope.launch {
            combine(
                preferencesManager.serverUrl,
                preferencesManager.username,
                preferencesManager.password
            ) { url, username, password ->
                Triple(url, username, password)
            }.collectLatest { (url, username, password) ->
                
                if (url != baseUrl || username != authManager.getAuthParams().first) {
                    reconfigure(url, username, password.toCharArray())
                }
            }
        }
    }

    @Synchronized
    private fun reconfigure(url: String, user: String, pass: CharArray) {
        components?.authManager?.clearPassword()
        repositoryScope.cancel()
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        val newAuthManager = AuthManager(user, pass)
        val newUrlBuilder = UrlBuilder(url, newAuthManager)
        val newApi = de.lwp2070809.speculonic.di.NetworkModule.provideSubsonicService(url)
        
        val newSyncManager = SyncManager(newApi, musicDao, newAuthManager, preferencesManager, entityMapper)
        val newPlaylistRepository = PlaylistRepository(newApi, musicDao, newAuthManager, entityMapper)
        val newUserActionRepository = UserActionRepository(newApi, musicDao, newAuthManager, entityMapper)
        val newLyricsRepository = LyricsRepository(context, newApi, musicDao, newAuthManager)
        val newMediaRepository = MediaRepository(newApi, musicDao, newAuthManager, entityMapper)
        val newCoverArtSyncManager = CoverArtSyncManager(context, url, musicDao, newUrlBuilder)

        components = Components(
            baseUrl = url,
            api = newApi,
            authManager = newAuthManager,
            urlBuilder = newUrlBuilder,
            syncManager = newSyncManager,
            playlistRepository = newPlaylistRepository,
            userActionRepository = newUserActionRepository,
            lyricsRepository = newLyricsRepository,
            mediaRepository = newMediaRepository,
            coverArtSyncManager = newCoverArtSyncManager
        )
    }

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && !baseUrl.contains("unconfigured")

    val syncManagerGet: SyncManager get() = syncManager
    val lyricsRepositoryGet: LyricsRepository get() = lyricsRepository
    val musicDaoGet: MusicDao get() = musicDao
    val preferencesManagerGet: PreferencesManager get() = preferencesManager
    val serverCapabilitiesGet: ServerCapabilities get() = serverCapabilities

    
    fun buildStreamUrl(id: String): String = urlBuilder.buildDownloadUrl(id)
    fun buildDownloadUrl(id: String): String = urlBuilder.buildDownloadUrl(id)
    fun buildCoverArtUrl(id: String): String = urlBuilder.buildCoverArtUrl(id)

    fun getCoverArtCacheKey(id: String): String {
        return coverArtSyncManager.getCoverArtCacheKey(id)
    }

    fun buildCoverArtRequest(id: String?, context: Context, preferLocal: Boolean = false, size: Int? = null, crossfade: Boolean = true): ImageRequest {
        return coverArtSyncManager.buildCoverArtRequest(id, preferLocal, size, crossfade)
    }

    suspend fun syncAllCoverArt(onProgress: (suspend (String) -> Unit)? = null) {
        coverArtSyncManager.syncAllCoverArt(onProgress)
    }

    suspend fun ping(): Boolean {
        val (u, t, s) = authManager.getAuthParams()
        
        
        val hasLocal = hasLocalData()
        val lastPing = preferencesManager.getLastPingTimeSync()
        val now = System.currentTimeMillis()
        val pingTTL = 12 * 60 * 60 * 1000L 
        if (hasLocal && (now - lastPing) < pingTTL && preferencesManager.getServerCapabilitiesSync() != null) {
            
            return true
        }

        return try {
            val response = api.ping(u, t, s)
            if (response.response.status == "ok") {
                updateCapabilities(response.response)
                if (serverCapabilities.isOpenSubsonic) {
                    discoverExtensions()
                }
                
                
                preferencesManager.saveServerCapabilities(serverCapabilities)
                preferencesManager.saveLastPingTime(System.currentTimeMillis())
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun hasLocalData(): Boolean {
        return musicDao.hasLocalData()
    }

    fun isSyncingFlow(): Flow<Boolean> = preferencesManager.isSyncing
    fun syncProgressFlow(): Flow<String?> = preferencesManager.syncProgress

    fun getAlbumsByListTypeFlow(type: String): Flow<List<Album>> = mediaRepository.getAlbumsByListTypeFlow(type)

    private fun resetReachableIfForced(forceRefresh: Boolean) {
        if (forceRefresh) {
            de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.reset()
        }
    }

    suspend fun refreshAlbumList(type: String = "newest", size: Int = 20, offset: Int = 0) {
        de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.reset()
        mediaRepository.refreshAlbumList(type, size, offset)
    }

    suspend fun refreshArtists() {
        de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.reset()
        mediaRepository.refreshArtists()
    }

    @Deprecated(
        message = "Use SyncAllDataUseCase instead to adhere to Clean Architecture",
        replaceWith = ReplaceWith("syncAllDataUseCase.invoke(...)")
    )
    suspend fun syncAllData(
        forceRefresh: Boolean = false,
        ignoreLastModified: Boolean = false,
        ignoreSafetyGuard: Boolean = false,
        onProgress: (suspend (String) -> Unit)? = null
    ) {
        if (!isConfigured) return
        val hasLocal = hasLocalData()
        syncManager.syncAllData(
            serverCapabilities = serverCapabilities,
            forceRefresh = forceRefresh,
            ignoreLastModified = ignoreLastModified,
            ignoreSafetyGuard = ignoreSafetyGuard,
            hasLocalData = hasLocal,
            onProgress = onProgress,
            onSyncComplete = { currentTime, serverLastModified ->
                getStarred(forceRefresh = true)
                refreshAlbumList("newest")
                refreshAlbumList("frequent")
                refreshAlbumList("random")
                musicDao.deleteOrphanedAlbums()
                preferencesManager.saveLastSyncTime(currentTime)
                if (serverLastModified != 0L) {
                    preferencesManager.saveServerLastModified(serverLastModified)
                }
            }
        )
        syncAllCoverArt(onProgress = onProgress)
        preferencesManager.saveLastFullSyncTime(System.currentTimeMillis())
        preloadPlaylistsAndSongs()
        if (forceRefresh || !hasLocal) {
            getStarred(forceRefresh = true)
        }
    }

    
    suspend fun quickSync(onProgress: (suspend (String) -> Unit)? = null) {
        if (!isConfigured) return
        de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.reset()
        syncManager.quickSync(
            onProgress = onProgress,
            onComplete = { currentTime ->
                
                coroutineScope {
                    val j1 = async { getStarred(forceRefresh = true) }
                    val j2 = async { preloadPlaylistsAndSongs() }
                    awaitAll(j1, j2)
                }
                preferencesManager.saveLastSyncTime(currentTime)
            }
        )
    }

    suspend fun preloadPlaylistsAndSongs() {
        try {
            val playlists = getPlaylists(forceRefresh = true)
            playlists.forEach { playlist ->
                getPlaylist(playlist.id, forceRefresh = true)
            }
        } catch (e: Exception) {
            LogManager.e("Failed to preload playlists and songs", e)
        }
    }

    private fun updateCapabilities(response: BaseResponse) {
        serverCapabilities = serverCapabilities.copy(
            type = response.type,
            serverVersion = response.serverVersion,
            isOpenSubsonic = response.openSubsonic == true
        )
    }

    private suspend fun discoverExtensions() {
        try {
            
            val (u, t, s) = authManager.getAuthParams()
            val response = api.getOpenSubsonicExtensions(u, t, s)
            val extensions = response.response.openSubsonicExtensions.map { it.name }.toList()
            serverCapabilities = serverCapabilities.copy(extensions = extensions)
        } catch (e: Exception) {
            LogManager.e("Failed to discover extensions", e)
        }
    }

    fun getArtistsFlow(): Flow<List<Artist>> = mediaRepository.getArtistsFlow()

    suspend fun getArtistDetails(id: String, forceRefresh: Boolean = false): Pair<Artist?, List<Album>> {
        resetReachableIfForced(forceRefresh)
        return mediaRepository.getArtistDetails(id, forceRefresh, skipInsert = false)
    }

    suspend fun getArtistInfo(id: String): ArtistInfo2? {
        return mediaRepository.getArtistInfo(id)
    }

    suspend fun getAlbumList(type: String = "newest", size: Int = 20, offset: Int = 0, forceRefresh: Boolean = false): List<Album> {
        resetReachableIfForced(forceRefresh)
        return mediaRepository.getAlbumList(type, size, offset, forceRefresh, hasLocalData(), skipInsert = false)
    }

    suspend fun getSongs(directoryId: String, forceRefresh: Boolean = false): List<Song> {
        resetReachableIfForced(forceRefresh)
        return mediaRepository.getSongs(directoryId, forceRefresh)
    }

    suspend fun getSongRemote(id: String): Result<Song> {
        return try {
            val (u, t, s) = authManager.getAuthParams()
            val response = api.getSong(id, u, t, s)
            val song = response.response.song
            if (response.response.status == "ok" && song != null) {
                Result.success(song)
            } else {
                Result.failure(Exception("Failed to get song or song is null"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAlbum(id: String, forceRefresh: Boolean = false): Album? {
        resetReachableIfForced(forceRefresh)
        return mediaRepository.getAlbum(id, forceRefresh, skipInsert = false)
    }

    fun getAlbumByIdFlow(id: String): Flow<Album?> = mediaRepository.getAlbumByIdFlow(id)

    fun getPlaylistByIdFlow(id: String): Flow<Playlist?> = playlistRepository.getPlaylistByIdFlow(id)



    fun getAllAlbumsFlow(): Flow<List<Album>> = mediaRepository.getAllAlbumsFlow()

    suspend fun getPlaylists(forceRefresh: Boolean = false): List<Playlist> {
        resetReachableIfForced(forceRefresh)
        return playlistRepository.getPlaylists(forceRefresh, hasLocalData(), skipInsert = false)
    }

    suspend fun getPlaylist(id: String, forceRefresh: Boolean = false): List<Song> {
        resetReachableIfForced(forceRefresh)
        return playlistRepository.getPlaylist(id, forceRefresh, skipInsert = false)
    }

    fun getPlaylistsFlow(): Flow<List<Playlist>> = playlistRepository.getPlaylistsFlow()

    fun getPinnedPlaylistsFlow(): Flow<List<Playlist>> = playlistRepository.getPinnedPlaylistsFlow()

    suspend fun togglePlaylistPinned(playlistId: String, pinned: Boolean) {
        playlistRepository.togglePlaylistPinned(playlistId, pinned)
    }

    suspend fun createPlaylist(name: String, songIds: List<String>? = null): Boolean {
        return playlistRepository.createPlaylist(name, songIds, hasLocalData())
    }

    suspend fun deletePlaylist(id: String): Boolean {
        return playlistRepository.deletePlaylist(id)
    }

    suspend fun addToPlaylist(playlistId: String, songId: String): Boolean {
        return playlistRepository.addToPlaylist(playlistId, songId, hasLocalData())
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int): Boolean {
        return playlistRepository.removeFromPlaylist(playlistId, index, hasLocalData())
    }

    suspend fun getStarred(forceRefresh: Boolean = false): List<Song> {
        resetReachableIfForced(forceRefresh)
        return userActionRepository.getStarred(forceRefresh, hasLocalData(), skipInsert = false)
    }

    suspend fun getStarredAlbums(forceRefresh: Boolean = false): List<Album> {
        resetReachableIfForced(forceRefresh)
        return userActionRepository.getStarredAlbums(forceRefresh, hasLocalData(), skipInsert = false)
    }

    fun getStarredFlow(): Flow<List<Song>> = userActionRepository.getStarredFlow()

    fun getStarredAlbumsFlow(): Flow<List<Album>> = userActionRepository.getStarredAlbumsFlow()

    fun getSongsByAlbumFlow(albumId: String): Flow<List<Song>> = mediaRepository.getSongsByAlbumFlow(albumId)

    fun getSongsByPlaylistFlow(playlistId: String): Flow<List<Song>> = mediaRepository.getSongsByPlaylistFlow(playlistId)

    fun getSongByIdFlow(songId: String): Flow<Song?> = mediaRepository.getSongByIdFlow(songId)

    fun getAllSongsPaged(): kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Song>> = mediaRepository.getAllSongsPaged()

    fun getAllSongsFlow(): Flow<List<Song>> = mediaRepository.getAllSongsFlow()

    suspend fun starSong(id: String, star: Boolean): Boolean = userActionRepository.starSong(id, star)

    suspend fun starAlbum(id: String, star: Boolean): Boolean = userActionRepository.starAlbum(id, star)

    suspend fun getLyricsData(songId: String, artist: String?, title: String?): Pair<String?, List<LyricLine>> {
        return lyricsRepository.getLyricsData(songId, artist, title, serverCapabilities.extensions.contains("songLyrics"))
    }

    suspend fun search(query: String): SearchResult3 = mediaRepository.search(query)
}
