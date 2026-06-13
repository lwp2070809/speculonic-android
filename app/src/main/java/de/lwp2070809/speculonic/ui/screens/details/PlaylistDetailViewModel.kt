package de.lwp2070809.speculonic.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val isLoading: Boolean = false,
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

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    
    @Volatile
    private var isUsingEphemeralData = false

    init {
        observeMetadata()
        observeSongs()
        loadPlaylistDetails(forceRefresh = false)
    }

    fun loadPlaylistDetails(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.playlist == null) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            try {
                
                val isSyncing = repository.isSyncingFlow().first()
                val songs = repository.getPlaylist(playlistId, forceRefresh = forceRefresh)
                
                
                if (isSyncing && songs.isNotEmpty()) {
                    isUsingEphemeralData = true
                }

                val playlists = repository.getPlaylists(forceRefresh = forceRefresh)
                val playlistMetadata = playlists.find { it.id == playlistId }
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false, 
                    songs = if (songs.isNotEmpty()) songs else _uiState.value.songs,
                    playlist = playlistMetadata ?: _uiState.value.playlist,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun observeMetadata() {
        viewModelScope.launch {
            repository.getPlaylistByIdFlow(playlistId).collectLatest { playlist ->
                if (playlist != null) {
                    _uiState.value = _uiState.value.copy(playlist = playlist)
                }
            }
        }
    }

    private fun observeSongs() {
        viewModelScope.launch {
            repository.getSongsByPlaylistFlow(playlistId).collectLatest { songs ->
                
                
                if (isUsingEphemeralData && songs.isEmpty()) {
                    return@collectLatest
                }
                
                _uiState.value = _uiState.value.copy(songs = songs)
                if (songs.isNotEmpty()) {
                    isUsingEphemeralData = false
                }
            }
        }
    }

    fun togglePinned() {
        val currentPlaylist = _uiState.value.playlist ?: return
        viewModelScope.launch {
            try {
                repository.togglePlaylistPinned(playlistId, !currentPlaylist.pinned)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}
