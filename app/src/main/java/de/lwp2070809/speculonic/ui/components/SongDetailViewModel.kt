package de.lwp2070809.speculonic.ui.components

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.SongAudioMetadataExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SongDetailUiState(
    val songId: String? = null,
    val sha1: String? = null,
    val id3Metadata: Map<String, String>? = null,
    val remoteSong: Song? = null,
    val remoteLoading: Boolean = false,
    val remoteError: String? = null
)

@HiltViewModel
class SongDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SubsonicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SongDetailUiState())
    val uiState: StateFlow<SongDetailUiState> = _uiState.asStateFlow()

    fun getSongEntityByIdFlow(songId: String): Flow<SongEntity?> {
        return repository.getSongEntityByIdFlow(songId)
    }

    suspend fun getSongRemote(songId: String): Result<Song> {
        return repository.getSongRemote(songId)
    }

    fun prepareForSong(songId: String) {
        if (_uiState.value.songId != songId) {
            _uiState.value = SongDetailUiState(songId = songId)
        }
    }

    fun loadSha1(songId: String, uri: String) {
        prepareForSong(songId)
        if (_uiState.value.sha1 != null) return
        viewModelScope.launch {
            val sha1 = SongAudioMetadataExtractor.calculateSha1(uri, context)
            if (_uiState.value.songId == songId) {
                _uiState.value = _uiState.value.copy(sha1 = sha1)
            }
        }
    }

    fun loadId3Metadata(songId: String, uri: String, suffix: String?, isTranscoded: Boolean) {
        prepareForSong(songId)
        if (_uiState.value.id3Metadata != null) return
        viewModelScope.launch {
            val metadata = SongAudioMetadataExtractor.extractId3(uri, suffix, isTranscoded, context)
            if (_uiState.value.songId == songId) {
                _uiState.value = _uiState.value.copy(id3Metadata = metadata)
            }
        }
    }

    fun fetchRemoteSong(songId: String, failedMessage: String) {
        prepareForSong(songId)
        if (_uiState.value.remoteSong != null || _uiState.value.remoteLoading) return
        if (ServerReachableManager.isOfflineOrUnreachable()) {
            _uiState.value = _uiState.value.copy(remoteError = "OFFLINE")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(remoteLoading = true, remoteError = null)
            val result = repository.getSongRemote(songId)
            if (_uiState.value.songId != songId) return@launch
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    remoteSong = result.getOrNull(),
                    remoteLoading = false
                )
            } else {
                val error = if (ServerReachableManager.isOfflineOrUnreachable()) {
                    "OFFLINE"
                } else {
                    result.exceptionOrNull()?.message ?: failedMessage
                }
                _uiState.value = _uiState.value.copy(
                    remoteError = error,
                    remoteLoading = false
                )
            }
        }
    }
}
