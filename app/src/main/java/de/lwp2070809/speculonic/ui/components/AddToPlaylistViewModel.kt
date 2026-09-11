package de.lwp2070809.speculonic.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.PlaylistAddResult
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

import kotlinx.coroutines.flow.update

data class AddToPlaylistUiState(
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = true,
    val processingPlaylistId: String? = null
)

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val repository: SubsonicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddToPlaylistUiState())
    val uiState: StateFlow<AddToPlaylistUiState> = _uiState.asStateFlow()

    init {
        observePlaylists()
        loadPlaylists()
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            repository.getPlaylistsFlow().collect { playlists ->
                _uiState.update { current ->
                    current.copy(
                        playlists = playlists,
                        isLoading = if (playlists.isNotEmpty()) false else current.isLoading
                    )
                }
            }
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            if (_uiState.value.playlists.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }
            try {
                val playlists = repository.getPlaylists(forceRefresh = true)
                _uiState.update { it.copy(playlists = playlists, isLoading = false) }
            } catch (e: Exception) {
                LogManager.e("AddToPlaylistViewModel: Failed to load playlists", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addSongToPlaylist(
        playlist: Playlist,
        song: Song,
        onResult: (PlaylistAddResult) -> Unit,
        onDismiss: () -> Unit
    ) {
        if (_uiState.value.processingPlaylistId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(processingPlaylistId = playlist.id)
            try {
                val existingSongs = repository.getPlaylist(playlist.id)
                if (existingSongs.any { it.id == song.id }) {
                    onResult(PlaylistAddResult.ALREADY_EXISTS)
                    onDismiss()
                } else {
                    val success = repository.addToPlaylist(playlist.id, song.id)
                    onResult(if (success) PlaylistAddResult.SUCCESS else PlaylistAddResult.ERROR)
                    onDismiss()
                }
            } catch (e: Exception) {
                LogManager.e("AddToPlaylistViewModel: Failed to add to playlist", e)
                onResult(PlaylistAddResult.ERROR)
                onDismiss()
            } finally {
                _uiState.value = _uiState.value.copy(processingPlaylistId = null)
            }
        }
    }
}
