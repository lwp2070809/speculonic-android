package de.lwp2070809.speculonic.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val isLoading: Boolean = false,
    val pinnedPlaylists: List<de.lwp2070809.speculonic.network.model.Playlist> = emptyList(),
    val favoriteSongs: List<Song> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val randomAlbums: List<Album> = emptyList(),
    val mostPlayed: List<Album> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    var hasHandledOfflineStartup = false

    init {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            hasHandledOfflineStartup = true
        }
        loadData()
        observeData()
        observeServerConfigChanges()
    }

    private fun observeServerConfigChanges() {
        viewModelScope.launch {
            repository.preferencesManagerGet.serverUrl.collectLatest { url ->
                if (url.isNotBlank() && !url.contains("unconfigured")) {
                    val hasData = repository.hasLocalData()
                    if (!hasData) {
                        LogManager.i("DiscoverViewModel: 监测到服务器配置首次就绪 - 立即触发首次全量同步以点亮主页！")
                        loadData()
                    }
                }
            }
        }
    }

    private fun observeData() {
        
        viewModelScope.launch {
            repository.getStarredFlow().collectLatest { songs ->
                _uiState.update { state ->
                    state.copy(favoriteSongs = songs.take(10))
                }
            }
        }

        
        viewModelScope.launch {
            repository.getPinnedPlaylistsFlow().collectLatest { playlists ->
                _uiState.update { state ->
                    state.copy(pinnedPlaylists = playlists)
                }
            }
        }

        
        viewModelScope.launch {
            repository.getStarredAlbumsFlow().collectLatest { albums ->
                _uiState.update { state ->
                    state.copy(favoriteAlbums = albums.take(10))
                }
            }
        }

        
        viewModelScope.launch {
            repository.getAlbumsByListTypeFlow("newest").collectLatest { albums ->
                _uiState.update { state ->
                    state.copy(recentlyAdded = albums.take(20))
                }
            }
        }

        
        viewModelScope.launch {
            repository.getAlbumsByListTypeFlow("frequent").collectLatest { albums ->
                _uiState.update { state ->
                    state.copy(mostPlayed = albums.take(20))
                }
            }
        }

        
        viewModelScope.launch {
            repository.getAlbumsByListTypeFlow("random").collectLatest { albums ->
                _uiState.update { state ->
                    state.copy(randomAlbums = albums.take(20))
                }
            }
        }
    }

    
    fun loadData() {
        viewModelScope.launch {
            val hasData = repository.hasLocalData()
            
            if (!hasData) {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    
                    syncAllDataUseCase()
                } catch (e: Exception) {
                    LogManager.e("DiscoverViewModel: initial sync failed", e)
                } finally {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    
    fun refreshData() {
        viewModelScope.launch {
            LogManager.d("DiscoverViewModel: Targeted refresh triggered.")
            _uiState.update { it.copy(isLoading = true) }
            try {
                
                val newest = repository.getAlbumList("newest", forceRefresh = true)
                val frequent = repository.getAlbumList("frequent", forceRefresh = true)
                val random = repository.getAlbumList("random", forceRefresh = true)
                val starredSongs = repository.getStarred(forceRefresh = true)
                val starredAlbums = repository.getStarredAlbums(forceRefresh = true)
                
                _uiState.update { state ->
                    state.copy(
                        recentlyAdded = if (newest.isNotEmpty()) newest else state.recentlyAdded,
                        mostPlayed = if (frequent.isNotEmpty()) frequent else state.mostPlayed,
                        randomAlbums = if (random.isNotEmpty()) random else state.randomAlbums,
                        favoriteSongs = if (starredSongs.isNotEmpty()) starredSongs.take(10) else state.favoriteSongs,
                        favoriteAlbums = if (starredAlbums.isNotEmpty()) starredAlbums.take(10) else state.favoriteAlbums,
                        error = null
                    )
                }
                LogManager.i("DiscoverViewModel: Targeted refresh completed.")
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: refreshData failed", e)
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    
    fun refreshFavoriteAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val starredAlbums = repository.getStarredAlbums(forceRefresh = true)
                _uiState.update { state ->
                    state.copy(
                        favoriteAlbums = if (starredAlbums.isNotEmpty()) starredAlbums.take(10) else state.favoriteAlbums,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: refreshFavoriteAlbums failed", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    
    fun playFavoriteSong(song: Song, playbackController: PlaybackController) {
        viewModelScope.launch {
            try {
                
                val allFavoriteSongs = repository.getStarred()
                val mediaItems = allFavoriteSongs.map { it.toMediaItem(repository) }
                val index = allFavoriteSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                if (mediaItems.isNotEmpty()) {
                    playbackController.play(mediaItems, index)
                }
            } catch (e: Exception) {
                LogManager.e("DiscoverViewModel: Play favorite song failed", e)
            }
        }
    }
}
