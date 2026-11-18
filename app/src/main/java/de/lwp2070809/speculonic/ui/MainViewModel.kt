package de.lwp2070809.speculonic.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.AppRuntimeCoordinator
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.data.UpdateManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.NetworkEvent
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.util.NetworkMonitor
import de.lwp2070809.speculonic.util.NetworkStatus
import de.lwp2070809.speculonic.util.PaletteUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    val preferencesManager: PreferencesManager,
    val playbackController: PlaybackController,
    val repository: SubsonicRepository,
    val downloadTracker: DownloadTracker,
    val downloadController: DownloadController,
    networkMonitor: NetworkMonitor,
    private val updateManager: UpdateManager,
    private val appRuntimeCoordinator: AppRuntimeCoordinator
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState
    val networkStatus: StateFlow<NetworkStatus> = networkMonitor.networkStatus

    val serverUrl: StateFlow<String?> = preferencesManager.serverUrl.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = null
    )

    val themeMode: StateFlow<ThemeMode> = preferencesManager.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = ThemeMode.SYSTEM
    )

    val colorMode: StateFlow<ColorMode> = preferencesManager.colorMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = ColorMode.ALBUM_COVER
    )

    val offlineMode: StateFlow<Boolean> = preferencesManager.offlineModeEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = false
    )

    val isEffectivelyOnline: StateFlow<Boolean> = combine(
        networkMonitor.networkStatus,
        preferencesManager.offlineModeEnabled
    ) { status, offline ->
        status.isOnline && !offline
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = networkMonitor.currentNetworkStatus.isOnline
        )

    val isStreamingAllowed: StateFlow<Boolean> = combine(
        networkMonitor.networkStatus,
        preferencesManager.mobilePlayAllowed,
        preferencesManager.offlineModeEnabled
    ) { status, allowed, offline ->
        status.isOnline && (!status.isMetered || allowed) && !offline
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
            initialValue = networkMonitor.currentNetworkStatus.let { it.isOnline && !it.isMetered }
        )

    val savedSeedColorLight: StateFlow<Int?> = preferencesManager.lastSeedColorLight.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = null
    )

    val savedSeedColorDark: StateFlow<Int?> = preferencesManager.lastSeedColorDark.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = null
    )

    private val _activeSeedColor = MutableStateFlow<Color?>(null)
    val activeSeedColor: StateFlow<Color?> = _activeSeedColor.asStateFlow()

    private val _hasEvaluatedColor = MutableStateFlow(false)
    val hasEvaluatedColor: StateFlow<Boolean> = _hasEvaluatedColor.asStateFlow()

    private val _updateAvailableResult = MutableStateFlow<UpdateManager.UpdateResult.UpdateAvailable?>(null)
    val updateAvailableResult: StateFlow<UpdateManager.UpdateResult.UpdateAvailable?> =
        _updateAvailableResult.asStateFlow()

    private val _toastMessageRes = MutableSharedFlow<Int>(extraBufferCapacity = TOAST_BUFFER_CAPACITY)
    val toastMessageRes: SharedFlow<Int> = _toastMessageRes.asSharedFlow()

    private var seedColorJob: Job? = null

    init {
        playbackController.ensureController()
        downloadTracker.init()
        appRuntimeCoordinator.reconcileStuckSyncOnAppLaunch()
        observeNetworkToastEvents()
        checkForUpdatesOnLaunch()
    }

    private fun observeNetworkToastEvents() {
        viewModelScope.launch {
            ServerReachableManager.networkEventFlow.collect { event ->
                val showToast = if (event == NetworkEvent.ServerOffline) {
                    preferencesManager.showOfflineToast.first()
                } else {
                    true
                }
                if (showToast) {
                    val resId = when (event) {
                        NetworkEvent.ServerOffline -> R.string.server_offline_toast
                        NetworkEvent.NetworkRestricted -> R.string.network_restricted_error
                    }
                    _toastMessageRes.tryEmit(resId)
                }
            }
        }
    }

    private fun checkForUpdatesOnLaunch() {
        viewModelScope.launch {
            val result = updateManager.checkForUpdates(manual = false)
            if (result is UpdateManager.UpdateResult.UpdateAvailable) {
                _updateAvailableResult.value = result
            }
        }
    }

    fun toggleOfflineMode() {
        viewModelScope.launch {
            val (newOffline, wasAutoOffline) = preferencesManager.toggleManualOfflineMode()
            if (wasAutoOffline) {
                _toastMessageRes.emit(R.string.auto_offline_disabled_toast)
            }
            val toastRes = if (newOffline) {
                R.string.offline_mode_enabled_toast
            } else {
                R.string.offline_mode_disabled_toast
            }
            _toastMessageRes.emit(toastRes)
        }
    }

    fun onAppVisibilityChanged(visible: Boolean) {
        playbackController.onAppVisibilityChanged(visible)
    }

    fun evaluateSeedColor(
        artworkUri: Uri?,
        hasCurrentSong: Boolean,
        colorMode: ColorMode,
        darkTheme: Boolean,
        fallbackSurfaceColor: Int,
        imageLoader: ImageLoader
    ) {
        seedColorJob?.cancel()
        seedColorJob = viewModelScope.launch {
            if (colorMode == ColorMode.SYSTEM_COLOR) {
                _activeSeedColor.value = null
                _hasEvaluatedColor.value = true
                preferencesManager.saveLastSeedColor(null, true)
                preferencesManager.saveLastSeedColor(null, false)
                return@launch
            }

            if (artworkUri != null) {
                val color = PaletteUtils.getSeedColorAsync(
                    context = appContext,
                    uri = artworkUri.toString(),
                    isDark = darkTheme,
                    imageLoader = imageLoader,
                    fallbackBackgroundColor = fallbackSurfaceColor
                )
                _activeSeedColor.value = color
                _hasEvaluatedColor.value = true
                preferencesManager.saveLastSeedColor(color?.toArgb(), darkTheme)
            } else {
                _activeSeedColor.value = null
                if (hasCurrentSong) {
                    _hasEvaluatedColor.value = true
                    preferencesManager.saveLastSeedColor(null, darkTheme)
                }
            }
        }
    }

    fun openUpdateBrowser(url: String) {
        updateManager.openBrowser(url)
        _updateAvailableResult.value = null
    }

    fun dismissUpdateDialog() {
        _updateAvailableResult.value = null
    }

    companion object {
        private const val STATE_STOP_TIMEOUT_MS = 5000L
        private const val TOAST_BUFFER_CAPACITY = 8
    }
}
