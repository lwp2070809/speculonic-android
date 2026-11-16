
package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.R

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import de.lwp2070809.speculonic.SpeculonicApp
import de.lwp2070809.speculonic.data.CacheSyncWorker
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogLevel
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject



import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val playbackController: PlaybackController,
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val testConnectionUseCase: de.lwp2070809.speculonic.domain.usecase.TestConnectionUseCase,
    private val verifyCacheConsistencyUseCase: de.lwp2070809.speculonic.domain.usecase.VerifyCacheConsistencyUseCase,
    private val resolveInconsistencyUseCase: de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase,
    private val database: de.lwp2070809.speculonic.data.db.AppDatabase,
    private val updateManager: de.lwp2070809.speculonic.data.UpdateManager,
    private val cacheOperations: CacheOperations
) : ViewModel() {

    private var workObserverJob: Job? = null
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val isSyncing: StateFlow<Boolean> = preferencesManager.isSyncing.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val syncProgress: StateFlow<String?> = preferencesManager.syncProgress.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val syncError: StateFlow<String?> = preferencesManager.syncError.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
    
    private var syncJob: kotlinx.coroutines.Job? = null
    private var testConnectionJob: Job? = null

    init {
        observePlaybackState()
        observePreferences()
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            playbackController.playbackState.collect { state ->
                _uiState.value = _uiState.value.copy(isPlaying = state.isPlaying)
            }
        }
    }

    private fun observePreferences() {
        // 1. 服务端与连接凭据配置
        viewModelScope.launch {
            combine(
                preferencesManager.serverUrl,
                preferencesManager.username,
                preferencesManager.password,
                preferencesManager.serverCapabilities,
                preferencesManager.allowInsecureConnections
            ) { url, user, pass, caps, allowInsecure ->
                _uiState.update { current ->
                    current.copy(
                        serverUrl = url,
                        username = user,
                        password = pass,
                        serverCapabilities = caps,
                        allowInsecureConnections = allowInsecure
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.credentialsResetWarning.collect { hasWarning ->
                _uiState.update { current ->
                    current.copy(credentialsResetWarning = hasWarning)
                }
            }
        }

        // 2. 音频控制与播放策略配置
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

        // 3. 车载蓝牙与歌词推送配置
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

        // 4. 存储、缓存限制与曲库统计
        viewModelScope.launch {
            combine(
                preferencesManager.cacheLocation,
                preferencesManager.maxCoverCacheSize,
                preferencesManager.syncCoverArtOnForce,
                preferencesManager.silentCacheEnabled
            ) { loc, maxCover, syncCover, silentCache ->
                _uiState.update { current ->
                    current.copy(
                        cacheLocation = loc,
                        maxCoverCacheSize = maxCover,
                        syncCoverArtOnForce = syncCover,
                        silentCacheEnabled = silentCache
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            combine(
                database.musicDao().getArtistsCountFlow(),
                database.musicDao().getAlbumsCountFlow(),
                database.musicDao().getSongsCountFlow(),
                database.musicDao().getPlaylistsCountFlow()
            ) { artists, albums, songs, playlists ->
                _uiState.update { current ->
                    current.copy(
                        artistsCount = artists,
                        albumsCount = albums,
                        songsCount = songs,
                        playlistsCount = playlists
                    )
                }
            }.collect()
        }

        // 5. 同步状态
        viewModelScope.launch {
            combine(
                preferencesManager.isSyncing,
                preferencesManager.syncProgress,
                preferencesManager.syncError,
                preferencesManager.lastSyncTime
            ) { syncing, progress, error, lastSync ->
                _uiState.update { current ->
                    current.copy(
                        isSyncing = syncing,
                        syncProgress = progress,
                        syncError = error,
                        lastSyncTime = lastSync
                    )
                }
            }.collect()
        }

        // 6. 系统偏好、外观主题与网络策略
        viewModelScope.launch {
            combine(
                preferencesManager.mobilePlayAllowed,
                preferencesManager.backgroundSyncEnabled,
                preferencesManager.logLevel,
                preferencesManager.themeMode,
                preferencesManager.colorMode
            ) { mobile, bgSync, log, theme, color ->
                _uiState.update { current ->
                    current.copy(
                        mobilePlayAllowed = mobile,
                        backgroundSyncEnabled = bgSync,
                        logLevel = log,
                        themeMode = theme,
                        colorMode = color
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            combine(
                preferencesManager.showOfflineToast,
                preferencesManager.updateCheckInterval,
                preferencesManager.autoOfflineOnMetered,
                preferencesManager.offlineModeEnabled,
                preferencesManager.playerBackgroundMode
            ) { showToast, interval, autoOffline, offlineMode, bgMode ->
                _uiState.update { current ->
                    current.copy(
                        showOfflineToast = showToast,
                        updateCheckInterval = interval,
                        autoOfflineOnMetered = autoOffline,
                        offlineModeEnabled = offlineMode,
                        playerBackgroundMode = bgMode,
                        language = getCurrentLanguageLabel()
                    )
                }
            }.collect()
        }
    }



    fun updateAutoOfflineOnMetered(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveAutoOfflineOnMetered(enabled)
        }
    }

    fun updateOfflineModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveOfflineModeEnabled(enabled)
        }
    }

    fun clearServerConfigTransientState() {
        testConnectionJob?.cancel()
        testConnectionJob = null
        _uiState.update {
            it.copy(
                isTestingConnection = false,
                testConnectionResult = null,
                urlError = null
            )
        }
    }

    private fun validateUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return try {
            java.net.URL(trimmed).toURI() != null
        } catch (e: Exception) {
            false
        }
    }

    fun testConnection(
        url: String,
        user: String,
        pass: String,
        allowInsecureConnections: Boolean = _uiState.value.allowInsecureConnections
    ) {
        val trimmedUrl = url.trim()
        val trimmedUser = user.trim()
        if (!validateUrl(trimmedUrl)) {
            _uiState.update {
                it.copy(
                    urlError = context.getString(de.lwp2070809.speculonic.R.string.error_invalid_url),
                    testConnectionResult = null
                )
            }
            return
        }

        val previousJob = testConnectionJob
        testConnectionJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            _uiState.update { it.copy(isTestingConnection = true, testConnectionResult = null, urlError = null) }
            try {
                val result = testConnectionUseCase(trimmedUrl, trimmedUser, pass, allowInsecureConnections)
                result.onSuccess {
                    _uiState.update { it.copy(testConnectionResult = true to null) }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            testConnectionResult = false to (e.message ?: context.getString(de.lwp2070809.speculonic.R.string.unknown_error))
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isTestingConnection = false) }
            }
        }
    }

    fun updateCacheLocation(location: String) {
        viewModelScope.launch {
            preferencesManager.saveCacheLocation(location)
            if (location.isNotEmpty()) {
                scanLocalFiles()
            } else {
                val cachedSongs = database.musicDao().getAllCachedSongs()
                cachedSongs.forEach { song ->
                    if (song.localUri != null && song.localUri.startsWith("content://")) {
                        database.musicDao().updateSongCacheStatus(song.id, null, false)
                    }
                }
            }
            refreshCacheSize()
        }
    }
    fun updateMaxCoverCacheSize(size: Long) { viewModelScope.launch { preferencesManager.saveMaxCoverCacheSize(size) } }
    
    fun updateMobilePlayAllowed(allowed: Boolean) { 
        viewModelScope.launch { 
            preferencesManager.saveMobilePlayAllowed(allowed)
            DownloadManagerHelper.updateRequirements(allowed)
        } 
    }

    fun updateShowOfflineToast(show: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveShowOfflineToast(show)
        }
    }

    fun updateBackgroundSyncEnabled(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveBackgroundSyncEnabled(enabled) } }

    fun updateLogLevel(level: LogLevel) {
        viewModelScope.launch {
            preferencesManager.saveLogLevel(level)
            LogManager.setMinLevel(level)
        }
    }
    fun updateThemeMode(mode: ThemeMode) { viewModelScope.launch { preferencesManager.saveThemeMode(mode) } }
    fun updateColorMode(mode: ColorMode) { viewModelScope.launch { preferencesManager.saveColorMode(mode) } }
    fun updatePlayerBackgroundMode(mode: de.lwp2070809.speculonic.data.PlayerBackgroundMode) { viewModelScope.launch { preferencesManager.savePlayerBackgroundMode(mode) } }
    
    fun updateSilentCacheEnabled(enabled: Boolean) { 
        if (!enabled) {
            _uiState.value = _uiState.value.copy(showSilentCacheConfirm = true)
        } else {
            viewModelScope.launch { preferencesManager.saveSilentCacheEnabled(true) }
        }
    }
    
    fun confirmDisableSilentCache() {
        viewModelScope.launch {
            preferencesManager.saveSilentCacheEnabled(false)
            _uiState.value = _uiState.value.copy(showSilentCacheConfirm = false)
        }
    }
    
    fun cancelDisableSilentCache() {
        _uiState.value = _uiState.value.copy(showSilentCacheConfirm = false)
    }


    fun updateBluetoothCarDeviceNames(deviceNames: Set<String>) {
        viewModelScope.launch {
            preferencesManager.saveBluetoothCarDeviceNames(deviceNames)
        }
    }

    fun updateUpdateCheckInterval(interval: de.lwp2070809.speculonic.data.UpdateCheckInterval) {
        viewModelScope.launch {
            preferencesManager.saveUpdateCheckInterval(interval)
        }
    }

    fun checkForUpdatesManually() {
        if (_uiState.value.isCheckingUpdate) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                when (val result = updateManager.checkForUpdates(manual = true)) {
                    is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.UpdateAvailable -> {
                        _uiState.update { it.copy(manualUpdateResult = result) }
                    }
                    is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.NoUpdate -> {
                        Toast.makeText(context, R.string.update_already_latest, Toast.LENGTH_SHORT).show()
                    }
                    is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.NotConfigured -> {
                        Toast.makeText(context, R.string.update_not_configured, Toast.LENGTH_SHORT).show()
                    }
                    is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.Error -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_check_failed, result.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun dismissManualUpdateDialog() {
        _uiState.update { it.copy(manualUpdateResult = null) }
    }

    fun openBrowser(url: String) {
        updateManager.openBrowser(url)
    }

    // 允许不安全连接选项已收敛为配置弹窗草稿态，仅在保存或删除服务器时统一落盘并切换网络状态

    fun updateCarBluetoothEnabled(enabled: Boolean) { 
        if (enabled) {
            val hasPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                _uiState.value = _uiState.value.copy(showBluetoothPermissionRequest = true)
                return
            }
        }
        viewModelScope.launch { preferencesManager.saveCarBluetoothEnabled(enabled) } 
    }
    
    fun updateSyncPlaybackState(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveSyncPlaybackState(enabled) } }
    fun updatePauseOnVolumeZero(enabled: Boolean) { viewModelScope.launch { preferencesManager.savePauseOnVolumeZero(enabled) } }
    fun updateSkipSilenceEnabled(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveSkipSilenceEnabled(enabled) } }
    fun updateDuckOnTransientFocusLoss(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveDuckOnTransientFocusLoss(enabled) } }
    fun updatePauseOnAudioFocusLoss(enabled: Boolean) { viewModelScope.launch { preferencesManager.savePauseOnAudioFocusLoss(enabled) } }
    fun updateTranscodeIncompatibleFormats(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveTranscodeIncompatibleFormats(enabled) } }
    fun updateTargetTranscodeFormat(format: String) { viewModelScope.launch { preferencesManager.saveTargetTranscodeFormat(format) } }

    fun updateBluetoothLyricsEnabled(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveBluetoothLyricsEnabled(enabled) } }
    fun updateBluetoothLyricsHideProgressBar(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveBluetoothLyricsHideProgressBar(enabled) } }

    fun addBluetoothCarDeviceName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            preferencesManager.addBluetoothCarDeviceName(name)
        }
    }

    fun removeBluetoothCarDeviceName(name: String) {
        viewModelScope.launch {
            preferencesManager.removeBluetoothCarDeviceName(name)
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(showBluetoothPermissionRequest = false)
        if (granted) {
            viewModelScope.launch { preferencesManager.saveCarBluetoothEnabled(true) }
        }
    }

    fun dismissBluetoothPermissionRequest() {
        _uiState.value = _uiState.value.copy(showBluetoothPermissionRequest = false)
    }

    fun saveSettings(
        rawUrl: String,
        rawUser: String,
        pass: String,
        syncCoverArt: Boolean = false,
        allowInsecureConnections: Boolean = _uiState.value.allowInsecureConnections
    ): Boolean {
        val url = rawUrl.trim()
        val user = rawUser.trim()
        if (!validateUrl(url)) {
            _uiState.update { it.copy(urlError = context.getString(de.lwp2070809.speculonic.R.string.error_invalid_url)) }
            return false
        }

        testConnectionJob?.cancel()
        testConnectionJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, isTestingConnection = false, urlError = null, testConnectionResult = null) }

            val oldUrl = preferencesManager.serverUrl.first()
            val oldUser = preferencesManager.username.first()

            val isServerChanged = oldUrl.isNotEmpty() && (url != oldUrl || user != oldUser)
            withContext(Dispatchers.IO) {
                preferencesManager.saveAllowInsecureConnections(allowInsecureConnections)
                if (isServerChanged) {
                    // 换服时先取消后台同步循环、应用新安全策略并驱逐连接池，避免旧服同步继续发起请求或写入即将清空的库表
                    stopSyncAndClearConnections(allowInsecureConnections)
                    NetworkModule.clearSubsonicServiceCache()
                    database.clearAllTables()
                    cacheOperations.clearAllCache(preserveSaf = false).onFailure { e ->
                        LogManager.e("SettingsViewModel: clearAllCache failed during server change", e)
                    }
                } else if (de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager.allowInsecureConnections && !allowInsecureConnections) {
                    // 安全降级时先取消在途同步再驱逐连接池，避免先断连触发异步异常并重复驱逐
                    stopSyncAndClearConnections(allowInsecureConnections)
                } else {
                    NetworkModule.rebuildClientIfNeeded(allowInsecureConnections)
                }
                NetworkModule.ServerReachableManager.reset()
            }

            _uiState.update {
                it.copy(
                    serverUrl = url,
                    username = user,
                    password = pass,
                    allowInsecureConnections = allowInsecureConnections
                )
            }
            preferencesManager.saveServerSettings(url, user, pass)

            withContext(Dispatchers.IO) {
                try {
                    repository.ping(force = true)
                } catch (e: Exception) {
                    LogManager.w("SettingsViewModel: Ping failed before sync: ${e.message}")
                }
            }

            if (!repository.hasLocalData() || oldUrl != url || oldUser != user) {
                if (url.isNotEmpty()) {
                    performFullSync(isForced = true, syncCoverArt = syncCoverArt)
                }
            }
            _uiState.update { it.copy(isSaving = false) }
        }
        return true
    }

    fun cancelFirstSync() { _uiState.value = _uiState.value.copy(showFirstSyncConfirm = false) }

    fun requestForceSync() { _uiState.value = _uiState.value.copy(showForceSyncConfirm = true) }
    fun cancelForceSync() { _uiState.value = _uiState.value.copy(showForceSyncConfirm = false) }
    fun confirmForceSync() { 
        _uiState.value = _uiState.value.copy(showForceSyncConfirm = false)
        performFullSync(isForced = true) 
    }

    fun updateSyncCoverArtOnForce(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveSyncCoverArtOnForce(enabled)
        }
    }

    private suspend fun performCoverArtSyncInternal() {
        try {
            preferencesManager.saveIsSyncing(true)
            preferencesManager.saveSyncProgress(context.getString(de.lwp2070809.speculonic.R.string.sync_cover_art_preparing))
            var lastNotifyTime = 0L
            repository.syncAllCoverArt(onProgress = { status ->
                val now = System.currentTimeMillis()
                if (now - lastNotifyTime >= 300L) {
                    lastNotifyTime = now
                    preferencesManager.saveSyncProgress(status)
                }
            })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("Settings: Cover Art Sync Failed", e)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                preferencesManager.saveIsSyncing(false)
                preferencesManager.saveSyncProgress(null)
            }
        }
    }

    fun performFullSync(
        ignoreSafetyGuard: Boolean = false,
        isForced: Boolean = false,
        syncCoverArt: Boolean = false
    ) {
        _uiState.update { it.copy(showFirstSyncConfirm = false, showSafetyGuardConfirm = false) }
        val previousSyncJob = syncJob
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            previousSyncJob?.cancelAndJoin()
            val shouldSyncCovers = repository.isConfigured && 
                (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
            var success = false
            try {
                preferencesManager.saveSyncError(null)
                preferencesManager.saveIsSyncing(true)
                preferencesManager.saveSyncProgress(context.getString(de.lwp2070809.speculonic.R.string.sync_preparing))
                syncAllDataUseCase(
                    forceRefresh = isForced, 
                    ignoreLastModified = isForced,
                    ignoreSafetyGuard = ignoreSafetyGuard,
                    keepSyncingState = shouldSyncCovers,
                    onProgress = { status ->
                        preferencesManager.saveSyncProgress(status)
                    }
                )
                success = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: de.lwp2070809.speculonic.domain.repository.SafetyGuardException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(showSafetyGuardConfirm = true, safetyGuardMessage = e.message)
                }
            } catch (e: Exception) {
                LogManager.e("Settings: Initial sync failed", e)
                preferencesManager.saveSyncError(e.message ?: e.toString())
            } finally {
                val shouldSyncCovers = success && repository.isConfigured && 
                    (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
                if (shouldSyncCovers) {
                    performCoverArtSyncInternal()
                } else {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        preferencesManager.saveIsSyncing(false)
                        preferencesManager.saveSyncProgress(null)
                    }
                }
            }
        }
    }

    private suspend fun stopSyncAndClearConnections(allowInsecureConnections: Boolean) {
        syncJob?.cancel()
        if (!NetworkModule.rebuildClientIfNeeded(allowInsecureConnections)) {
            NetworkModule.cancelAndEvictConnections()
        }
        syncJob?.join()
        syncJob = null
    }

    fun confirmSafetyGuard() { performFullSync(ignoreSafetyGuard = true, isForced = true) }
    fun cancelSafetyGuard() { _uiState.value = _uiState.value.copy(showSafetyGuardConfirm = false, safetyGuardMessage = null) }
    
    fun deleteServerSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            // 先于清除凭据终止同步任务与在途请求、驱逐连接池并重置不安全连接策略，防止删除后仍有旧会话复用
            stopSyncAndClearConnections(allowInsecureConnections = false)
            NetworkModule.clearSubsonicServiceCache()
            preferencesManager.saveAllowInsecureConnections(false)
            preferencesManager.saveServerSettings("", "", "")
            
            database.clearAllTables()
            cacheOperations.clearAllCache(preserveSaf = false).onFailure { e ->
                LogManager.e("SettingsViewModel: clearAllCache failed during server deletion", e)
            }
            NetworkModule.ServerReachableManager.reset()
            
            _uiState.update {
                it.copy(
                    serverUrl = "", 
                    username = "", 
                    password = "",
                    testConnectionResult = null,
                    allowInsecureConnections = false,
                    urlError = null
                )
            }
        }
    }


    fun requestClearCache(target: CacheClearTarget = CacheClearTarget.ALL) {
        _uiState.value = _uiState.value.copy(pendingClearCacheTarget = target)
    }

    fun cancelClearCache() {
        _uiState.value = _uiState.value.copy(pendingClearCacheTarget = null)
    }

    fun confirmClearCache() {
        val target = _uiState.value.pendingClearCacheTarget ?: return
        _uiState.value = _uiState.value.copy(pendingClearCacheTarget = null)
        when (target) {
            CacheClearTarget.ALL -> clearCache()
            CacheClearTarget.PLAYBACK -> clearPlaybackCache()
            CacheClearTarget.COVER_ART -> clearCoverArtCache()
            CacheClearTarget.SONGS -> clearSongDownloads()
        }
    }

    fun clearCache() {
        _uiState.value = _uiState.value.copy(pendingClearCacheTarget = null)
        viewModelScope.launch {
            val result = cacheOperations.clearAllCache(preserveSaf = true)
            result.onSuccess {
                LogManager.i("Settings: Internal cache cleared.")
                refreshCacheSize()
                scanLocalFiles()
            }.onFailure { e ->
                showCacheOperationFailureToast(e)
            }
        }
    }

    fun requestSyncWithServer() {
        if (isUnmeteredNetwork()) syncWithServer()
        else _uiState.value = _uiState.value.copy(showMobileSyncConfirm = true)
    }

    fun cancelMobileSync() { _uiState.value = _uiState.value.copy(showMobileSyncConfirm = false) }

    fun syncWithServer() {
        _uiState.value = _uiState.value.copy(showMobileSyncConfirm = false)
        if (_uiState.value.isPlaying) playbackController.togglePlayPause()
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isInteractiveScanning = true,
                interactiveScanProgress = 0,
                interactiveScanStatus = context.getString(de.lwp2070809.speculonic.R.string.sync_preparing),
                showInconsistencyDialog = false,
                inconsistentItems = emptyList()
            )

            try {
                withContext(Dispatchers.IO) {
                    syncAllDataUseCase(
                        forceRefresh = false,
                        ignoreLastModified = false,
                        ignoreSafetyGuard = false,
                        onProgress = { status ->
                            _uiState.value = _uiState.value.copy(interactiveScanStatus = status)
                        }
                    )
                }
            } catch (e: de.lwp2070809.speculonic.domain.repository.SafetyGuardException) {
                _uiState.value = _uiState.value.copy(
                    isInteractiveScanning = false,
                    showSafetyGuardConfirm = true,
                    safetyGuardMessage = e.message
                )
                return@launch
            } catch (e: Exception) {
                LogManager.e("Settings: Lightweight sync failed before cache check", e)
            }

            val cacheLocation = preferencesManager.cacheLocation.first()
            verifyCacheConsistencyUseCase(cacheLocation).collect { state ->
                when (state) {
                    is de.lwp2070809.speculonic.domain.usecase.VerifyCacheState.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            isInteractiveScanning = true,
                            interactiveScanProgress = state.percentage,
                            interactiveScanStatus = state.status
                        )
                    }
                    is de.lwp2070809.speculonic.domain.usecase.VerifyCacheState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isInteractiveScanning = false,
                            showInconsistencyDialog = true,
                            inconsistentItems = state.inconsistentItems
                        )
                    }
                    is de.lwp2070809.speculonic.domain.usecase.VerifyCacheState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isInteractiveScanning = false,
                            showInconsistencyDialog = true,
                            inconsistentItems = emptyList()
                        )
                    }
                }
            }
        }
    }

    fun dismissInconsistencyDialog() {
        _uiState.value = _uiState.value.copy(showInconsistencyDialog = false, inconsistentItems = emptyList())
    }

    fun resolveInconsistentItem(item: InconsistentItem, action: String) {
        viewModelScope.launch {
            val useCaseAction = if (action == "DELETE") {
                de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase.Action.DELETE
            } else {
                de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase.Action.REDOWNLOAD
            }
            val result = resolveInconsistencyUseCase(item, useCaseAction)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    inconsistentItems = _uiState.value.inconsistentItems.filter { it.id != item.id }
                )
            }
        }
    }

    fun scanLocalFiles() {
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            syncPercentage = 0,
            syncProgress = context.getString(de.lwp2070809.speculonic.R.string.stop_and_scan)
        )
        if (_uiState.value.isPlaying) playbackController.togglePlayPause()
        
        CacheSyncWorker.runOnce(context, forceScan = true, healCovers = false)
        observeWorkProgress("CacheSync_Once") { copy(isScanning = false, isSyncing = false) }
    }

    private fun observeWorkProgress(uniqueName: String, onFinished: SettingsUiState.() -> SettingsUiState) {
        workObserverJob?.cancel()
        workObserverJob = viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(uniqueName)
                .collect { workInfos ->
                    val info = workInfos.firstOrNull() ?: return@collect
                    val progress = info.progress.getInt(CacheSyncWorker.PROGRESS, 0)
                    val status = info.progress.getString(CacheSyncWorker.STATUS)
                    
                    _uiState.value = _uiState.value.copy(syncPercentage = progress, syncProgress = status)
                    
                    if (info.state.isFinished) {
                        refreshCacheSize()
                        _uiState.value = onFinished(_uiState.value).copy(syncPercentage = null, syncProgress = null)
                        workObserverJob?.cancel()
                    }
                }
        }
    }

    private fun isUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            val location = preferencesManager.cacheLocation.first()
            val breakdown = cacheOperations.calculateCacheSizes(location)
            val (freeSpace, totalSpace, cachedSongs) = withContext(Dispatchers.IO) {
                val free = try { context.cacheDir.freeSpace } catch (e: Exception) { 0L }
                val total = try { context.cacheDir.totalSpace } catch (e: Exception) { 0L }
                val songsCount = database.musicDao().getCachedSongsCount()
                Triple(free, total, songsCount)
            }
            val totalInternal = breakdown.playbackBytes + breakdown.coverArtBytes + breakdown.songBytes + breakdown.otherBytes
            val isCoverOverQuota = breakdown.coverArtBytes > _uiState.value.maxCoverCacheSize
            _uiState.value = _uiState.value.copy(
                internalCacheSize = cacheOperations.formatFileSize(totalInternal),
                externalCacheSize = cacheOperations.formatFileSize(breakdown.externalBytes),
                internalCacheBytes = totalInternal,
                externalCacheBytes = breakdown.externalBytes,
                freeSpaceBytes = freeSpace,
                totalSpaceBytes = totalSpace,
                cachedSongsCount = cachedSongs,
                isCoverOverQuota = isCoverOverQuota,
                playbackCacheBytes = breakdown.playbackBytes,
                coverArtCacheBytes = breakdown.coverArtBytes,
                songCacheBytes = breakdown.songBytes,
                otherCacheBytes = breakdown.otherBytes,
                playbackCacheSize = cacheOperations.formatFileSize(breakdown.playbackBytes),
                coverArtCacheSize = cacheOperations.formatFileSize(breakdown.coverArtBytes),
                songCacheSize = cacheOperations.formatFileSize(breakdown.songBytes),
                otherCacheSize = cacheOperations.formatFileSize(breakdown.otherBytes)
            )
        }
    }

    private fun showCacheOperationFailureToast(error: Throwable) {
        viewModelScope.launch(Dispatchers.Main) {
            val msg = error.message ?: error.toString()
            Toast.makeText(context, context.getString(R.string.clear_cache_failed, msg), Toast.LENGTH_SHORT).show()
        }
    }

    fun clearPlaybackCache() {
        viewModelScope.launch {
            val result = cacheOperations.clearPlaybackCache()
            result.onSuccess {
                refreshCacheSize()
            }.onFailure { e ->
                showCacheOperationFailureToast(e)
            }
        }
    }

    fun clearCoverArtCache() {
        viewModelScope.launch {
            val result = cacheOperations.clearCoverArtCache()
            result.onSuccess {
                refreshCacheSize()
            }.onFailure { e ->
                showCacheOperationFailureToast(e)
            }
        }
    }

    fun clearSongDownloads() {
        viewModelScope.launch {
            val result = cacheOperations.clearSongDownloads()
            result.onSuccess {
                refreshCacheSize()
            }.onFailure { e ->
                showCacheOperationFailureToast(e)
            }
        }
    }

    fun setLanguage(languageCode: String) {
        val appLocale = if (languageCode == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageCode)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
        _uiState.value = _uiState.value.copy(language = getLanguageLabel(languageCode))
    }

    private fun getCurrentLanguageLabel(): String {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        return if (!currentLocales.isEmpty) {
            getLanguageLabel(currentLocales.get(0)?.toLanguageTag() ?: "system")
        } else {
            context.getString(R.string.system)
        }
    }

    private fun getLanguageLabel(code: String): String = when {
        code.startsWith("en") -> "English"
        code.startsWith("zh") -> "简体中文"
        else -> context.getString(R.string.system)
    }


}

