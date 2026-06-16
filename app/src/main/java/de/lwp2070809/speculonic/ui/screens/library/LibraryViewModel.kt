package de.lwp2070809.speculonic.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isInitialLoadComplete: Boolean = false,
    val syncProgress: String? = null,
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val allSongsPaged: kotlinx.coroutines.flow.Flow<PagingData<Song>>? = null,
    val favorites: List<Song> = emptyList(),
    val selectedTabIndex: Int = 0,
    val error: String? = null,
    val showSafetyGuardConfirm: Boolean = false,
    val safetyGuardMessage: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val uiState: StateFlow<LibraryUiState> = combine(
        _uiState,
        repository.isSyncingFlow(),
        repository.syncProgressFlow(),
        repository.getAllAlbumsFlow().debounceExceptFirst(500),
        repository.getArtistsFlow().debounceExceptFirst(500),
        repository.getStarredFlow(),
        repository.getPlaylistsFlow()
    ) { args: Array<Any?> ->
        val state = args[0] as LibraryUiState
        @Suppress("UNCHECKED_CAST")
        state.copy(
            isSyncing = args[1] as Boolean,
            syncProgress = args[2] as String?,
            albums = args[3] as List<Album>,
            artists = args[4] as List<Artist>,
            favorites = args[5] as List<Song>,
            playlists = args[6] as List<Playlist>,
            allSongsPaged = state.allSongsPaged ?: repository.getAllSongsPaged().cachedIn(viewModelScope),
            isInitialLoadComplete = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(allSongsPaged = repository.getAllSongsPaged().cachedIn(viewModelScope))
    )

    init {
        loadData()
    }

    fun setSelectedTabIndex(index: Int) {
        _uiState.update { it.copy(selectedTabIndex = index) }
    }

    fun resetNav() {
        _uiState.update { it.copy(selectedTabIndex = 0) }
    }

    
    fun loadData() {
        viewModelScope.launch {
            val hasLocalData = repository.hasLocalData()
            
            if (!hasLocalData) {
                _uiState.update { it.copy(isLoading = true) }
                
                launch {
                    try {
                        repository.getPlaylists(forceRefresh = true)
                    } catch (e: Exception) {
                        LogManager.e("LibraryViewModel: Initial playlist fetch failed", e)
                    } finally {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }

                launch {
                    try {
                        repository.getStarred(forceRefresh = true)
                    } catch (e: Exception) {
                        LogManager.e("LibraryViewModel: Initial starred fetch failed", e)
                    }
                }
            }
            
        }
    }

    
    fun refreshAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                
                repository.refreshAlbumList(type = "alphabeticalByName", size = 2000)
                _uiState.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshAlbums failed", e)
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    
    fun refreshArtists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                repository.refreshArtists()
                _uiState.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshArtists failed", e)
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    
    fun refreshQuickly() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                repository.quickSync()
                _uiState.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshQuickly failed", e)
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    
    fun refreshDeeply(ignoreLastModified: Boolean = false, ignoreSafetyGuard: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true, 
                syncProgress = "正在执行全量同步...", 
                error = null,
                showSafetyGuardConfirm = false
            ) }
            try {
                syncAllDataUseCase(
                    forceRefresh = true, 
                    ignoreLastModified = ignoreLastModified,
                    ignoreSafetyGuard = ignoreSafetyGuard,
                    onProgress = { status ->
                        _uiState.update { it.copy(syncProgress = status) }
                    }
                )
                _uiState.update { it.copy(isLoading = false, syncProgress = null) }
            } catch (e: de.lwp2070809.speculonic.domain.repository.SafetyGuardException) {
                _uiState.update { it.copy(
                    isLoading = false,
                    syncProgress = null,
                    showSafetyGuardConfirm = true,
                    safetyGuardMessage = e.message
                ) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshDeeply failed", e)
                _uiState.update { it.copy(
                    isLoading = false,
                    syncProgress = null,
                    error = e.message ?: "Unknown error"
                ) }
            }
        }
    }

    
    fun refreshFavorites() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                repository.getStarred(forceRefresh = true)
                _uiState.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshFavorites failed", e)
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    
    fun refreshPlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                repository.getPlaylists(forceRefresh = true)
                _uiState.update { it.copy(isRefreshing = false) }
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: refreshPlaylists failed", e)
                _uiState.update { it.copy(isRefreshing = false, error = e.message) }
            }
        }
    }

    fun confirmSafetyGuard() {
        refreshDeeply(ignoreLastModified = true, ignoreSafetyGuard = true)
    }

    fun cancelSafetyGuard() {
        _uiState.update { it.copy(showSafetyGuardConfirm = false, safetyGuardMessage = null) }
    }



    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                repository.createPlaylist(name)
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: createPlaylist failed", e)
            }
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            try {
                repository.deletePlaylist(id)
            } catch (e: Exception) {
                LogManager.e("LibraryViewModel: deletePlaylist failed", e)
            }
        }
    }
}


fun <T> Flow<T>.debounceExceptFirst(timeoutMillis: Long): Flow<T> = channelFlow {
    var isFirst = true
    collectLatest { value ->
        if (isFirst) {
            isFirst = false
            send(value)
        } else {
            delay(timeoutMillis)
            send(value)
        }
    }
}
