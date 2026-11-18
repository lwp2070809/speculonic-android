package de.lwp2070809.speculonic.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaybackBluetoothUiState(
    val pauseOnVolumeZero: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val duckOnTransientFocusLoss: Boolean = true,
    val pauseOnAudioFocusLoss: Boolean = true,
    val transcodeIncompatibleFormats: Boolean = false,
    val targetTranscodeFormat: String = "mp3",
    val carBluetoothEnabled: Boolean = false,
    val syncPlaybackState: Boolean = false,
    val bluetoothLyricsEnabled: Boolean = false,
    val bluetoothLyricsHideProgressBar: Boolean = false,
    val bluetoothCarDeviceNames: Set<String> = emptySet(),
    val showBluetoothPermissionRequest: Boolean = false
)

@HiltViewModel
class PlaybackBluetoothViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PlaybackBluetoothUiState(
            transcodeIncompatibleFormats = preferencesManager.getCachedTranscodeIncompatibleFormats()
        )
    )
    val uiState: StateFlow<PlaybackBluetoothUiState> = _uiState.asStateFlow()

    init {
        observePlaybackPrefs()
        observeBluetoothPrefs()
    }

    private fun observePlaybackPrefs() {
        viewModelScope.launch {
            combine(
                preferencesManager.skipSilenceEnabled,
                preferencesManager.duckOnTransientFocusLoss,
                preferencesManager.pauseOnAudioFocusLoss,
                preferencesManager.transcodeIncompatibleFormats,
                preferencesManager.targetTranscodeFormat
            ) { skip, duck, pause, transcode, targetFmt ->
                _uiState.update { current ->
                    current.copy(
                        skipSilenceEnabled = skip,
                        duckOnTransientFocusLoss = duck,
                        pauseOnAudioFocusLoss = pause,
                        transcodeIncompatibleFormats = transcode,
                        targetTranscodeFormat = targetFmt
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.pauseOnVolumeZero.collect { pauseZero ->
                _uiState.update { current ->
                    current.copy(pauseOnVolumeZero = pauseZero)
                }
            }
        }
    }

    private fun observeBluetoothPrefs() {
        viewModelScope.launch {
            combine(
                preferencesManager.carBluetoothEnabled,
                preferencesManager.syncPlaybackState,
                preferencesManager.bluetoothLyricsEnabled,
                preferencesManager.bluetoothLyricsHideProgressBar,
                preferencesManager.bluetoothCarDeviceNames
            ) { carEnabled, syncState, lyricsEnabled, hideProgress, deviceNames ->
                _uiState.update { current ->
                    current.copy(
                        carBluetoothEnabled = carEnabled,
                        syncPlaybackState = syncState,
                        bluetoothLyricsEnabled = lyricsEnabled,
                        bluetoothLyricsHideProgressBar = hideProgress,
                        bluetoothCarDeviceNames = deviceNames
                    )
                }
            }.collect()
        }
    }

    fun updatePauseOnVolumeZero(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.savePauseOnVolumeZero(enabled) }
    }

    fun updateSkipSilenceEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveSkipSilenceEnabled(enabled) }
    }

    fun updateDuckOnTransientFocusLoss(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveDuckOnTransientFocusLoss(enabled) }
    }

    fun updatePauseOnAudioFocusLoss(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.savePauseOnAudioFocusLoss(enabled) }
    }

    fun updateTranscodeIncompatibleFormats(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveTranscodeIncompatibleFormats(enabled) }
    }

    fun updateTargetTranscodeFormat(format: String) {
        viewModelScope.launch { preferencesManager.saveTargetTranscodeFormat(format) }
    }

    fun updateCarBluetoothEnabled(enabled: Boolean) {
        if (enabled) {
            val hasPermission = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                _uiState.update { it.copy(showBluetoothPermissionRequest = true) }
                return
            }
        }
        viewModelScope.launch { preferencesManager.saveCarBluetoothEnabled(enabled) }
    }

    fun updateSyncPlaybackState(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveSyncPlaybackState(enabled) }
    }

    fun updateBluetoothLyricsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveBluetoothLyricsEnabled(enabled) }
    }

    fun updateBluetoothLyricsHideProgressBar(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveBluetoothLyricsHideProgressBar(enabled) }
    }

    fun addBluetoothCarDeviceName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { preferencesManager.addBluetoothCarDeviceName(name) }
    }

    fun removeBluetoothCarDeviceName(name: String) {
        viewModelScope.launch { preferencesManager.removeBluetoothCarDeviceName(name) }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(showBluetoothPermissionRequest = false) }
        if (granted) {
            viewModelScope.launch { preferencesManager.saveCarBluetoothEnabled(true) }
        }
    }
}
