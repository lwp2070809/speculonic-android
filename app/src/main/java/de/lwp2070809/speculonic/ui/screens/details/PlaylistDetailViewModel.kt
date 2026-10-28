package de.lwp2070809.speculonic.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.PlaylistNotFoundException
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaylistDetailUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isDeleted: Boolean = false,
    val shouldAutoExit: Boolean = false,
    val isModifying: Boolean = false,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val error: String? = null
)

@HiltViewModel(assistedFactory = PlaylistDetailViewModel.Factory::class)
class PlaylistDetailViewModel @AssistedInject constructor(
    private val repository: SubsonicRepository,
    @Assisted private val playlistId: String
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(playlistId: String): PlaylistDetailViewModel
    }

    private val playlistOperationMutex = Mutex()
    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    
    init {
        observeMetadata()
        observeSongs()
        loadPlaylistDetails(forceRefresh = false)
    }

    fun loadPlaylistDetails(forceRefresh: Boolean = false, isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isManualRefresh) {
                _uiState.update { it.copy(isRefreshing = true, error = null) }
            }

            try {
                if (!forceRefresh) {
                    val cachedPlaylist = repository.getCachedPlaylist(playlistId)
                    val cachedSongs = repository.getCachedPlaylistSongs(playlistId)

                    if (cachedPlaylist != null || cachedSongs.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                playlist = cachedPlaylist ?: it.playlist,
                                songs = if (cachedSongs.isNotEmpty()) cachedSongs else it.songs,
                                isLoading = false,
                                error = null
                            )
                        }
                        if (cachedSongs.isEmpty()) {
                            try {
                                repository.getPlaylist(playlistId, forceRefresh = true)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                if (e is PlaylistNotFoundException) {
                                    LogManager.w("PlaylistDetailViewModel: Playlist $playlistId was deleted on server")
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            isRefreshing = false,
                                            isDeleted = true,
                                            shouldAutoExit = true,
                                            playlist = null,
                                            songs = emptyList(),
                                            error = null
                                        )
                                    }
                                } else {
                                    LogManager.e("PlaylistDetailViewModel: fetch songs failed for $playlistId", e)
                                }
                            }
                        }
                    } else {
                        if (!isManualRefresh) {
                            _uiState.update { it.copy(isLoading = true, error = null) }
                        }
                        repository.getPlaylist(playlistId, forceRefresh = true)
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = null) }
                    }
                } else {
                    if (!isManualRefresh) {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                    }
                    repository.getPlaylist(playlistId, forceRefresh = true)
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = null) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is PlaylistNotFoundException) {
                    LogManager.w("PlaylistDetailViewModel: Playlist $playlistId was deleted on server")
                    val hadContent = _uiState.value.playlist != null || isManualRefresh
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isDeleted = true,
                            shouldAutoExit = hadContent,
                            playlist = null,
                            songs = emptyList(),
                            error = null
                        )
                    }
                } else {
                    LogManager.e("PlaylistDetailViewModel: loadPlaylistDetails failed", e)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = if (it.playlist == null || isManualRefresh) e.message else null
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun observeMetadata() {
        viewModelScope.launch {
            repository.getPlaylistByIdFlow(playlistId).collectLatest { playlist ->
                _uiState.update { it.copy(playlist = playlist) }
            }
        }
    }

    private fun observeSongs() {
        viewModelScope.launch {
            repository.getSongsByPlaylistFlow(playlistId).collectLatest { songs ->
                _uiState.update { it.copy(songs = songs) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun togglePinned() {
        val currentPlaylist = _uiState.value.playlist ?: return
        viewModelScope.launch {
            try {
                repository.togglePlaylistPinned(playlistId, !currentPlaylist.pinned)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleStarSong(songId: String, star: Boolean) {
        viewModelScope.launch {
            try {
                repository.starSong(songId, star)
            } catch (e: Exception) {
                de.lwp2070809.speculonic.util.LogManager.e("PlaylistDetailViewModel: toggleStarSong failed", e)
            }
        }
    }

    fun removeFromPlaylist(songId: String, fallbackIndex: Int = -1, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            playlistOperationMutex.withLock {
                _uiState.update { it.copy(isModifying = true) }
                try {
                    val currentSongs = _uiState.value.songs
                    val actualIndex = currentSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: fallbackIndex
                    if (actualIndex < 0) {
                        LogManager.w("PlaylistDetailViewModel: removeFromPlaylist skipped, song $songId not found")
                        onResult(false)
                        return@withLock
                    }
                    val success = repository.removeFromPlaylist(playlistId, actualIndex)
                    onResult(success)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogManager.e("PlaylistDetailViewModel: removeFromPlaylist failed", e)
                    onResult(false)
                } finally {
                    _uiState.update { it.copy(isModifying = false) }
                }
            }
        }
    }

    fun removeFromPlaylist(index: Int, onResult: (Boolean) -> Unit = {}) {
        val songId = _uiState.value.songs.getOrNull(index)?.id ?: ""
        removeFromPlaylist(songId = songId, fallbackIndex = index, onResult = onResult)
    }
}
