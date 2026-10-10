package de.lwp2070809.speculonic.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AlbumDetailUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val album: Album? = null,
    val songs: List<Song> = emptyList(),
    val error: String? = null
)

@HiltViewModel(assistedFactory = AlbumDetailViewModel.Factory::class)
class AlbumDetailViewModel @AssistedInject constructor(
    private val repository: SubsonicRepository,
    @Assisted private val albumId: String
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(albumId: String): AlbumDetailViewModel
    }

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        
        observeMetadata()
        observeSongs()
        
        loadAlbumDetails(forceRefresh = false)
    }

    fun loadAlbumDetails(forceRefresh: Boolean = false, isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isManualRefresh) {
                _uiState.value = _uiState.value.copy(isRefreshing = true)
            }

            try {
                if (!forceRefresh) {
                    val cachedAlbum = repository.getCachedAlbum(albumId)
                    if (cachedAlbum != null) {
                        _uiState.value = _uiState.value.copy(
                            album = cachedAlbum,
                            songs = if (cachedAlbum.song.isNotEmpty()) cachedAlbum.song else _uiState.value.songs,
                            isLoading = false,
                            error = null
                        )
                        if (cachedAlbum.song.isEmpty()) {
                            try {
                                repository.getAlbum(albumId, forceRefresh = true)
                            } catch (e: Exception) {
                                LogManager.e("AlbumDetailViewModel: fetch songs failed for $albumId", e)
                            }
                        }
                    } else {
                        if (!isManualRefresh) {
                            _uiState.value = _uiState.value.copy(isLoading = true)
                        }
                        repository.getAlbum(albumId, forceRefresh = true)
                        _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false, error = null)
                    }
                } else {
                    if (!isManualRefresh) {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                    repository.getAlbum(albumId, forceRefresh = true)
                    _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false, error = null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LogManager.e("AlbumDetailViewModel: loadAlbumDetails failed", e)
                if (_uiState.value.album == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, isRefreshing = false)
                }
            }
        }
    }

    private fun observeMetadata() {
        viewModelScope.launch {
            repository.getAlbumByIdFlow(albumId).collectLatest { album ->
                _uiState.value = _uiState.value.copy(album = album)
            }
        }
    }

    private fun observeSongs() {
        viewModelScope.launch {
            repository.getSongsByAlbumFlow(albumId).collectLatest { songs ->
                _uiState.value = _uiState.value.copy(songs = songs)
            }
        }
    }

    fun toggleStar() {
        val currentAlbum = _uiState.value.album ?: return
        val isStarred = currentAlbum.starred != null
        viewModelScope.launch {
            repository.starAlbum(albumId, !isStarred)
        }
    }

    fun toggleStarSong(songId: String, star: Boolean) {
        viewModelScope.launch {
            try {
                repository.starSong(songId, star)
            } catch (e: Exception) {
                de.lwp2070809.speculonic.util.LogManager.e("AlbumDetailViewModel: toggleStarSong failed", e)
            }
        }
    }
}
