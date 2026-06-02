
package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.R

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val cacheLocation: String = "",
    val internalCacheSize: String = "0 B",
    val externalCacheSize: String = "0 B",
    val maxCacheSize: Long = 1024L * 1024 * 1024,
    val mobilePlayAllowed: Boolean = true,
    val showOfflineToast: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorMode: ColorMode = ColorMode.ALBUM_COVER,
    val silentCacheEnabled: Boolean = true,
    
    val carBluetoothEnabled: Boolean = false,
    val syncPlaybackState: Boolean = true,
    val bluetoothLyricsEnabled: Boolean = false,
    val bluetoothLyricsHideProgressBar: Boolean = false,
    val bluetoothCarDeviceNames: Set<String> = emptySet(),
    
    val isSaving: Boolean = false,
    val isRefreshing: Boolean = false,
    val isScanning: Boolean = false,
    val isSyncing: Boolean = false,
    val isPlaying: Boolean = false,
    val syncProgress: String? = null,
    val syncPercentage: Int? = null,
    val showFirstSyncConfirm: Boolean = false,
    val showSafetyGuardConfirm: Boolean = false,
    val safetyGuardMessage: String? = null,

    val isInteractiveScanning: Boolean = false,
    val interactiveScanProgress: Int = 0,
    val interactiveScanStatus: String = "",
    val showInconsistencyDialog: Boolean = false,
    val inconsistentItems: List<InconsistentItem> = emptyList(),

    val showSilentCacheConfirm: Boolean = false,
    val showBluetoothPermissionRequest: Boolean = false,

    val language: String = "System",
    val isTestingConnection: Boolean = false,
    val testConnectionResult: Pair<Boolean, String?>? = null,
    val urlError: String? = null,
    val showMobileSyncConfirm: Boolean = false,
    val showClearCacheConfirm: Boolean = false,
    val showCoverArtSyncConfirm: Boolean = false,
    val showForceSyncConfirm: Boolean = false,
    val trustAllCertificates: Boolean = false,
    val playerBackgroundMode: de.lwp2070809.speculonic.data.PlayerBackgroundMode = de.lwp2070809.speculonic.data.PlayerBackgroundMode.GAUSSIAN_BLUR
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val playbackController: PlaybackController,
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val testConnectionUseCase: de.lwp2070809.speculonic.domain.usecase.TestConnectionUseCase,
    private val verifyCacheConsistencyUseCase: de.lwp2070809.speculonic.domain.usecase.VerifyCacheConsistencyUseCase,
    private val resolveInconsistencyUseCase: de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase,
    private val database: de.lwp2070809.speculonic.data.db.AppDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()
    
    private val context = SpeculonicApp.instance
    private val cacheOperations = CacheOperations(context)

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
        viewModelScope.launch {
            
            val group1 = combine(
                preferencesManager.serverUrl,
                preferencesManager.username,
                preferencesManager.password,
                preferencesManager.cacheLocation,
                preferencesManager.maxCacheSize,
            ) { serverUrl, username, password, cacheLocation, maxCacheSize ->
                PrefsGroup1(serverUrl, username, password, cacheLocation, maxCacheSize)
            }

            
            val group2 = combine(
                preferencesManager.mobilePlayAllowed,
                preferencesManager.backgroundSyncEnabled,
                preferencesManager.logLevel,
                preferencesManager.themeMode,
                preferencesManager.colorMode,
            ) { mobilePlayAllowed, backgroundSyncEnabled, logLevel, themeMode, colorMode ->
                PrefsGroup2(mobilePlayAllowed, backgroundSyncEnabled, logLevel, themeMode, colorMode)
            }

            
            val bluetoothGroup = combine(
                preferencesManager.carBluetoothEnabled,
                preferencesManager.syncPlaybackState,
                preferencesManager.bluetoothLyricsEnabled,
                preferencesManager.bluetoothLyricsHideProgressBar,
                preferencesManager.bluetoothCarDeviceNames
            ) { carEnabled, syncState, lyricsEnabled, hideProgress, deviceNames ->
                BluetoothPrefs(carEnabled, syncState, lyricsEnabled, hideProgress, deviceNames)
            }

            val group3 = combine(
                preferencesManager.silentCacheEnabled,
                preferencesManager.playerBackgroundMode,
                bluetoothGroup
            ) { silentCacheEnabled, backgroundMode, bluetooth ->
                PrefsGroup3(silentCacheEnabled, backgroundMode, bluetooth)
            }

            val group4 = combine(
                preferencesManager.trustAllCertificates,
                preferencesManager.showOfflineToast,
                group3
            ) { trustAllCertificates, showOfflineToast, g3 ->
                Triple(trustAllCertificates, showOfflineToast, g3)
            }

            combine(group1, group2, group4) { g1, g2, g4 ->
                SettingsUiState(
                    serverUrl = g1.serverUrl,
                    username = g1.username,
                    password = g1.password,
                    cacheLocation = g1.cacheLocation,
                    maxCacheSize = g1.maxCacheSize,
                    mobilePlayAllowed = g2.mobilePlayAllowed,
                    showOfflineToast = g4.second,
                    backgroundSyncEnabled = g2.backgroundSyncEnabled,
                    logLevel = g2.logLevel,
                    themeMode = g2.themeMode,
                    colorMode = g2.colorMode,
                    playerBackgroundMode = g4.third.backgroundMode,
                    silentCacheEnabled = g4.third.silentCacheEnabled,
                    
                    carBluetoothEnabled = g4.third.bluetooth.carEnabled,
                    syncPlaybackState = g4.third.bluetooth.syncState,
                    bluetoothLyricsEnabled = g4.third.bluetooth.lyricsEnabled,
                    bluetoothLyricsHideProgressBar = g4.third.bluetooth.hideProgress,
                    bluetoothCarDeviceNames = g4.third.bluetooth.deviceNames,
                    
                    language = getCurrentLanguageLabel(),
                    isPlaying = _uiState.value.isPlaying,
                    internalCacheSize = _uiState.value.internalCacheSize,
                    externalCacheSize = _uiState.value.externalCacheSize,
                    showSilentCacheConfirm = _uiState.value.showSilentCacheConfirm,
                    isInteractiveScanning = _uiState.value.isInteractiveScanning,
                    interactiveScanProgress = _uiState.value.interactiveScanProgress,
                    interactiveScanStatus = _uiState.value.interactiveScanStatus,
                    showInconsistencyDialog = _uiState.value.showInconsistencyDialog,
                    inconsistentItems = _uiState.value.inconsistentItems,
                    trustAllCertificates = g4.first,
                    isSaving = _uiState.value.isSaving,
                    isRefreshing = _uiState.value.isRefreshing,
                    isScanning = _uiState.value.isScanning,
                    isSyncing = _uiState.value.isSyncing,
                    syncProgress = _uiState.value.syncProgress,
                    syncPercentage = _uiState.value.syncPercentage,
                    showFirstSyncConfirm = _uiState.value.showFirstSyncConfirm,
                    showSafetyGuardConfirm = _uiState.value.showSafetyGuardConfirm,
                    safetyGuardMessage = _uiState.value.safetyGuardMessage,
                    showBluetoothPermissionRequest = _uiState.value.showBluetoothPermissionRequest,
                    isTestingConnection = _uiState.value.isTestingConnection,
                    testConnectionResult = _uiState.value.testConnectionResult,
                    urlError = _uiState.value.urlError,
                    showMobileSyncConfirm = _uiState.value.showMobileSyncConfirm,
                    showClearCacheConfirm = _uiState.value.showClearCacheConfirm,
                    showCoverArtSyncConfirm = _uiState.value.showCoverArtSyncConfirm,
                    showForceSyncConfirm = _uiState.value.showForceSyncConfirm
                )
            }.collect {
                _uiState.value = it
                refreshCacheSize()
            }
        }
    }

    private data class PrefsGroup1(
        val serverUrl: String, val username: String, val password: String,
        val cacheLocation: String, val maxCacheSize: Long
    )
    private data class PrefsGroup2(
        val mobilePlayAllowed: Boolean, val backgroundSyncEnabled: Boolean,
        val logLevel: LogLevel, val themeMode: ThemeMode, val colorMode: ColorMode
    )
    private data class BluetoothPrefs(
        val carEnabled: Boolean, val syncState: Boolean,
        val lyricsEnabled: Boolean, val hideProgress: Boolean, val deviceNames: Set<String>
    )
    private data class PrefsGroup3(
        val silentCacheEnabled: Boolean,
        val backgroundMode: de.lwp2070809.speculonic.data.PlayerBackgroundMode,
        val bluetooth: BluetoothPrefs
    )

    fun updateServerUrl(url: String) { 
        _uiState.value = _uiState.value.copy(serverUrl = url, urlError = null, testConnectionResult = null) 
    }
    fun updateUsername(user: String) { _uiState.value = _uiState.value.copy(username = user, testConnectionResult = null) }
    fun updatePassword(pass: String) { _uiState.value = _uiState.value.copy(password = pass, testConnectionResult = null) }
    
    private fun validateUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return true 
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return try {
            java.net.URL(trimmed).toURI() != null
        } catch (e: Exception) {
            false
        }
    }

    fun testConnection(url: String, user: String, pass: String) {
        if (!validateUrl(url)) {
            _uiState.value = _uiState.value.copy(urlError = context.getString(de.lwp2070809.speculonic.R.string.error_invalid_url))
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingConnection = true, testConnectionResult = null, urlError = null)
            val result = testConnectionUseCase(url, user, pass)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(testConnectionResult = true to null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(testConnectionResult = false to (e.message ?: "Unknown error"))
            }
            _uiState.value = _uiState.value.copy(isTestingConnection = false)
        }
    }

    fun updateCacheLocation(location: String) {
        viewModelScope.launch {
            preferencesManager.saveCacheLocation(location)
            if (location.isNotEmpty()) scanLocalFiles()
        }
    }
    fun updateMaxCacheSize(size: Long) { viewModelScope.launch { preferencesManager.saveMaxCacheSize(size) } }
    
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


    fun updateTrustAllCertificates(trust: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveTrustAllCertificates(trust)
            
            NetworkModule.rebuildClientIfNeeded(trust)
        }
    }

    fun updateCarBluetoothEnabled(enabled: Boolean) { 
        if (enabled && android.os.Build.VERSION.SDK_INT >= 31) {
            val hasPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                _uiState.value = _uiState.value.copy(showBluetoothPermissionRequest = true)
                return
            }
        }
        viewModelScope.launch { preferencesManager.saveCarBluetoothEnabled(enabled) } 
    }
    
    fun updateSyncPlaybackState(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveSyncPlaybackState(enabled) } }
    fun updateBluetoothLyricsEnabled(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveBluetoothLyricsEnabled(enabled) } }
    fun updateBluetoothLyricsHideProgressBar(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveBluetoothLyricsHideProgressBar(enabled) } }

    fun addBluetoothCarDeviceName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val currentNames = preferencesManager.bluetoothCarDeviceNames.first().toMutableSet()
            if (currentNames.add(name)) {
                preferencesManager.saveBluetoothCarDeviceNames(currentNames)
            }
        }
    }

    fun removeBluetoothCarDeviceName(name: String) {
        viewModelScope.launch {
            val currentNames = preferencesManager.bluetoothCarDeviceNames.first().toMutableSet()
            if (currentNames.remove(name)) {
                preferencesManager.saveBluetoothCarDeviceNames(currentNames)
            }
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

    fun saveSettings(): Boolean {
        val url = _uiState.value.serverUrl
        if (!validateUrl(url)) {
            _uiState.value = _uiState.value.copy(urlError = context.getString(de.lwp2070809.speculonic.R.string.error_invalid_url))
            return false
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val user = _uiState.value.username
            val pass = _uiState.value.password

            val oldUrl = preferencesManager.serverUrl.first()
            val oldUser = preferencesManager.username.first()
            
            
            if (oldUrl.isNotEmpty() && (url != oldUrl || user != oldUser)) {
                withContext(Dispatchers.IO) {
                    database.clearAllTables()
                    cacheOperations.clearAllCache()
                }
            }

            preferencesManager.saveServerSettings(url, user, pass)
            
            
            if (!repository.hasLocalData()) {
                if (isUnmeteredNetwork()) performFullSync(isForced = true)
                else _uiState.value = _uiState.value.copy(showFirstSyncConfirm = true)
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
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

    fun performFullSync(ignoreSafetyGuard: Boolean = false, isForced: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            showFirstSyncConfirm = false, 
            showSafetyGuardConfirm = false,
            isSyncing = true,
            syncProgress = context.getString(de.lwp2070809.speculonic.R.string.sync_preparing)
        )
        _isSyncing.value = true
        _syncProgress.value = context.getString(de.lwp2070809.speculonic.R.string.sync_preparing)
        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                syncAllDataUseCase(
                    forceRefresh = isForced, 
                    ignoreLastModified = isForced,
                    ignoreSafetyGuard = ignoreSafetyGuard,
                    onProgress = { status ->
                        _syncProgress.value = status
                        _uiState.value = _uiState.value.copy(syncProgress = status)
                    }
                )
                success = true
            } catch (e: de.lwp2070809.speculonic.domain.repository.SafetyGuardException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(showSafetyGuardConfirm = true, safetyGuardMessage = e.message)
                }
            } catch (e: Exception) {
                LogManager.e("Settings: Initial sync failed", e)
            } finally {
                val showConfirm = success && repository.isConfigured
                if (!showConfirm) {
                    _isSyncing.value = false
                    _syncProgress.value = null
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false, 
                        syncProgress = null
                    )
                }
                _uiState.value = _uiState.value.copy(
                    showCoverArtSyncConfirm = showConfirm
                )
            }
        }
    }

    fun confirmSafetyGuard() { performFullSync(ignoreSafetyGuard = true, isForced = true) }
    fun cancelSafetyGuard() { _uiState.value = _uiState.value.copy(showSafetyGuardConfirm = false, safetyGuardMessage = null) }
    
    fun deleteServerSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            preferencesManager.saveServerSettings("", "", "")
            
            
            database.clearAllTables()
            cacheOperations.clearAllCache()
            
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    serverUrl = "", 
                    username = "", 
                    password = "",
                    testConnectionResult = null,
                    urlError = null
                )
            }
        }
    }

    fun cancelCoverArtSync() {
        _isSyncing.value = false
        _syncProgress.value = null
        _uiState.value = _uiState.value.copy(showCoverArtSyncConfirm = false, isSyncing = false, syncProgress = null)
    }

    @kotlin.OptIn(DelicateCoroutinesApi::class)
    fun startCoverArtSync() {
        _isSyncing.value = false
        _syncProgress.value = null
        _uiState.value = _uiState.value.copy(showCoverArtSyncConfirm = false, isSyncing = false, syncProgress = null)
        GlobalScope.launch(Dispatchers.IO) {
            val notificationManager = NotificationManagerCompat.from(context)
            val channelId = "cover_art_sync_channel"
            val notificationId = 1002
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Cover Art Sync",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Syncs cover art in the background"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(de.lwp2070809.speculonic.R.string.sync_now))
                .setContentText(context.getString(de.lwp2070809.speculonic.R.string.sync_cover_art_preparing))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(notificationId, builder.build())
            }

            try {
                
                
                
                
                
                var lastNotifyTime = 0L
                repository.syncAllCoverArt(onProgress = { status ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyTime >= 300L) {
                        lastNotifyTime = now
                        builder.setContentText(status)
                        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                })
            } catch (e: Exception) {
                LogManager.e("Settings: Cover Art Sync Failed", e)
            } finally {
                builder.setContentText(context.getString(de.lwp2070809.speculonic.R.string.sync_cover_art_completed))
                    .setOngoing(false)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(notificationId, builder.build())
                }
            }
        }
    }

    fun requestClearCache() { _uiState.value = _uiState.value.copy(showClearCacheConfirm = true) }
    fun cancelClearCache() { _uiState.value = _uiState.value.copy(showClearCacheConfirm = false) }

    fun clearCache() {
        _uiState.value = _uiState.value.copy(showClearCacheConfirm = false)
        viewModelScope.launch {
            cacheOperations.clearAllCache()
            LogManager.i("Settings: Internal cache cleared.")
            scanLocalFiles()
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
            val useCaseAction = if (action == "DELETE") de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase.Action.DELETE else de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase.Action.REDOWNLOAD
            val result = resolveInconsistencyUseCase(item, useCaseAction)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    inconsistentItems = _uiState.value.inconsistentItems.filter { it.id != item.id }
                )
            }
        }
    }

    fun scanLocalFiles() {
        _uiState.value = _uiState.value.copy(isScanning = true, syncPercentage = 0, syncProgress = context.getString(de.lwp2070809.speculonic.R.string.stop_and_scan))
        if (_uiState.value.isPlaying) playbackController.togglePlayPause()
        
        CacheSyncWorker.runOnce(context, forceScan = true, healCovers = false)
        observeWorkProgress("CacheSync_Once") { copy(isScanning = false, isSyncing = false) }
    }

    private fun observeWorkProgress(uniqueName: String, onFinished: SettingsUiState.() -> SettingsUiState) {
        viewModelScope.launch {
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
            val (internal, external) = cacheOperations.calculateCacheSizes(location)
            _uiState.value = _uiState.value.copy(
                internalCacheSize = cacheOperations.formatFileSize(internal),
                externalCacheSize = cacheOperations.formatFileSize(external)
            )
        }
    }

    fun setLanguage(languageCode: String) {
        val appLocale = if (languageCode == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
        _uiState.value = _uiState.value.copy(language = getLanguageLabel(languageCode))
    }

    private fun getCurrentLanguageLabel(): String {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        return if (!currentLocales.isEmpty) getLanguageLabel(currentLocales.get(0)?.toLanguageTag() ?: "system") else "System"
    }

    private fun getLanguageLabel(code: String): String = when {
        code.startsWith("en") -> "English"
        code.startsWith("zh") -> "简体中文"
        else -> "System"
    }
}

