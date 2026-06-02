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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AlbumDetailUiState(
    val isLoading: Boolean = false,
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

    fun loadAlbumDetails(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            
            
            if (forceRefresh || _uiState.value.album == null) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            try {
                
                
                val cachedAlbum = repository.getAlbum(albumId, forceRefresh = false)
                if (cachedAlbum != null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = null)
                }

                
                repository.getAlbum(albumId, forceRefresh = true)
                _uiState.value = _uiState.value.copy(isLoading = false, error = null)
            } catch (e: Exception) {
                
                if (_uiState.value.album == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
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
            val success = repository.starAlbum(albumId, !isStarred)
            if (success) {
                _uiState.value = _uiState.value.copy(
                    album = currentAlbum.copy(starred = if (!isStarred) "true" else null)
                )
            }
        }
    }
}
