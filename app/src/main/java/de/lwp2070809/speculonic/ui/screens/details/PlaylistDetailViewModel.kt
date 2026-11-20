package de.lwp2070809.speculonic.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.PreferencesManager
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
    val transcodeIncompatible: Boolean = false,
    val activeDownloads: Set<String> = emptySet(),
    val downloadedIds: Set<String> = emptySet(),
    val error: String? = null
)

@HiltViewModel(assistedFactory = PlaylistDetailViewModel.Factory::class)
class PlaylistDetailViewModel @AssistedInject constructor(
    private val repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager,
    private val downloadTracker: DownloadTracker,
    @Assisted private val playlistId: String
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(playlistId: String): PlaylistDetailViewModel
    }

    private val playlistOperationMutex = Mutex()
    private val _uiState = MutableStateFlow(
        PlaylistDetailUiState(transcodeIncompatible = preferencesManager.getCachedTranscodeIncompatibleFormats())
    )
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    
    init {
        observePreferences()
        observeDownloads()
        observeMetadata()
        observeSongs()
        loadPlaylistDetails(forceRefresh = false)
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesManager.transcodeIncompatibleFormats.collectLatest { enabled ->
                _uiState.update { it.copy(transcodeIncompatible = enabled) }
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            downloadTracker.activeDownloadIds.collectLatest { activeIds ->
                _uiState.update { it.copy(activeDownloads = activeIds) }
            }
        }
        viewModelScope.launch {
            downloadTracker.downloadedSongIds.collectLatest { downloadedIds ->
                _uiState.update { it.copy(downloadedIds = downloadedIds) }
            }
        }
    }

    fun loadPlaylistDetails(forceRefresh: Boolean = false, isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isManualRefresh) {
                _uiState.update { it.copy(isRefreshing = true, error = null) }
            }
            try {
                if (forceRefresh || !tryEmitCachedPlaylistDetails()) {
                    fetchRemotePlaylistDetails(isManualRefresh)
                }
            } catch (e: Exception) {
                handlePlaylistLoadError(e, isManualRefresh)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun tryEmitCachedPlaylistDetails(): Boolean {
        val cachedPlaylist = repository.getCachedPlaylist(playlistId)
        val cachedSongs = repository.getCachedPlaylistSongs(playlistId)
        if (cachedPlaylist == null && cachedSongs.isEmpty()) return false

        _uiState.update {
            it.copy(
                playlist = cachedPlaylist ?: it.playlist,
                songs = cachedSongs.ifEmpty { it.songs },
                isLoading = false,
                error = null
            )
        }
        if (cachedSongs.isEmpty()) {
            refreshMissingPlaylistSongs()
        }
        return true
    }

    private suspend fun refreshMissingPlaylistSongs() {
        try {
            repository.getPlaylist(playlistId, forceRefresh = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is PlaylistNotFoundException) {
                markPlaylistDeleted(shouldAutoExit = true)
            } else {
                LogManager.e("PlaylistDetailViewModel: fetch songs failed for $playlistId", e)
            }
        }
    }

    private suspend fun fetchRemotePlaylistDetails(isManualRefresh: Boolean) {
        if (!isManualRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        repository.getPlaylist(playlistId, forceRefresh = true)
        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = null) }
    }

    private fun handlePlaylistLoadError(e: Exception, isManualRefresh: Boolean) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (e is PlaylistNotFoundException) {
            val hadContent = _uiState.value.playlist != null || isManualRefresh
            markPlaylistDeleted(shouldAutoExit = hadContent)
            return
        }
        LogManager.e("PlaylistDetailViewModel: loadPlaylistDetails failed", e)
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                error = if (it.playlist == null || isManualRefresh) e.message else null
            )
        }
    }

    private fun markPlaylistDeleted(shouldAutoExit: Boolean) {
        LogManager.w("PlaylistDetailViewModel: Playlist $playlistId was deleted on server")
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isDeleted = true,
                shouldAutoExit = shouldAutoExit,
                playlist = null,
                songs = emptyList(),
                error = null
            )
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleStarSong(songId: String, star: Boolean) {
        viewModelScope.launch {
            try {
                repository.starSong(songId, star)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                de.lwp2070809.speculonic.util.LogManager.e("PlaylistDetailViewModel: toggleStarSong failed", e)
            }
        }
    }

    fun removeFromPlaylist(
        songId: String,
        fallbackIndex: Int,
        selectedSongs: List<Song>,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            playlistOperationMutex.withLock {
                _uiState.update { it.copy(isModifying = true) }
                try {
                    val currentSongs = repository.getCachedPlaylistSongs(playlistId)
                    val actualIndex = resolvePlaylistRemoval(selectedSongs, currentSongs, songId, fallbackIndex)
                    if (actualIndex == null) {
                        loadPlaylistDetails(forceRefresh = true)
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

}

internal fun resolvePlaylistRemoval(selectedSongs: List<Song>, currentSongs: List<Song>, songId: String, index: Int): Int? {
    if (currentSongs.getOrNull(index)?.id == songId && selectedSongs.size == currentSongs.size &&
        selectedSongs.indices.all { selectedSongs[it].id == currentSongs[it].id }
    ) return index
    return currentSongs.indices.singleOrNull { currentSongs[it].id == songId }
}
