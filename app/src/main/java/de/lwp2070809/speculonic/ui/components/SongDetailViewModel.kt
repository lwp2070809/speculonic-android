package de.lwp2070809.speculonic.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.CopyLimit
import de.lwp2070809.speculonic.util.CopyLimitException
import de.lwp2070809.speculonic.util.SongAudioMetadataExtractor
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Sha1State {
    data object Ready : Sha1State
    data object Reading : Sha1State
    data object Failed : Sha1State
    data class Digest(val value: String) : Sha1State
}

data class SongDetailUiState(
    val songId: String? = null,
    val localUri: String? = null,
    val sha1: Sha1State = Sha1State.Ready,
    val id3Limit: CopyLimit? = null,
    val id3Metadata: Map<String, String>? = null,
    val remoteSong: Song? = null,
    val remoteLoading: Boolean = false,
    val remoteError: String? = null
)

@HiltViewModel
class SongDetailViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val metadataExtractor: SongAudioMetadataExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(SongDetailUiState())
    val uiState: StateFlow<SongDetailUiState> = _uiState.asStateFlow()

    fun getSongEntityByIdFlow(songId: String): Flow<SongEntity?> {
        return repository.getSongEntityByIdFlow(songId)
    }

    private var sha1Job: Job? = null
    private var id3Job: Job? = null

    fun bindLocalFile(songId: String, uri: String?) {
        val current = _uiState.value
        if (current.songId == songId && current.localUri == uri) return
        sha1Job?.cancel()
        id3Job?.cancel()
        val song = if (current.songId == songId) current else SongDetailUiState(songId = songId)
        _uiState.value = song.copy(localUri = uri, sha1 = Sha1State.Ready, id3Metadata = null, id3Limit = null)
    }

    fun loadSha1(songId: String, uri: String) {
        val current = _uiState.value
        if (current.songId != songId || current.localUri != uri) return
        if (current.sha1 == Sha1State.Reading || current.sha1 is Sha1State.Digest) return
        _uiState.value = current.copy(sha1 = Sha1State.Reading)
        sha1Job = viewModelScope.launch {
            val digest = metadataExtractor.calculateSha1(uri)
            _uiState.update { state ->
                if (state.songId == songId && state.localUri == uri) {
                    state.copy(sha1 = digest?.let(Sha1State::Digest) ?: Sha1State.Failed)
                } else state
            }
        }
    }

    fun loadId3Metadata(songId: String, uri: String, suffix: String?, isTranscoded: Boolean) {
        val current = _uiState.value
        if (current.songId != songId || current.localUri != uri) return
        if (current.id3Metadata != null || current.id3Limit != null || id3Job?.isActive == true) return
        id3Job = viewModelScope.launch {
            try {
                val metadata = metadataExtractor.extractId3(uri, suffix, isTranscoded)
                _uiState.update { if (it.songId == songId && it.localUri == uri) it.copy(id3Metadata = metadata) else it }
            } catch (e: CopyLimitException) {
                _uiState.update { if (it.songId == songId && it.localUri == uri) it.copy(id3Limit = e.limit) else it }
            }
        }
    }

    fun fetchRemoteSong(songId: String, failedMessage: String) {
        val current = _uiState.value
        if (current.remoteSong != null || current.remoteLoading) return
        if (ServerReachableManager.isOfflineOrUnreachable()) {
            _uiState.update { state ->
                if (state.songId == songId) state.copy(remoteError = "OFFLINE") else state
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { state ->
                if (state.songId == songId) state.copy(remoteLoading = true, remoteError = null) else state
            }
            val result = repository.getSongRemote(songId)
            _uiState.update { state ->
                if (state.songId != songId) {
                    state
                } else if (result.isSuccess) {
                    state.copy(
                        remoteSong = result.getOrNull(),
                        remoteLoading = false
                    )
                } else {
                    val error = if (ServerReachableManager.isOfflineOrUnreachable()) {
                        "OFFLINE"
                    } else {
                        result.exceptionOrNull()?.message ?: failedMessage
                    }
                    state.copy(
                        remoteError = error,
                        remoteLoading = false
                    )
                }
            }
        }
    }
}
