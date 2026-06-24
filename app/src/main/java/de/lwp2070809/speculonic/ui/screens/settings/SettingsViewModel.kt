
package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.R

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
            
            val group1 = combine<Any?, PrefsGroup1>(
                preferencesManager.serverUrl,
                preferencesManager.username,
                preferencesManager.password,
                preferencesManager.cacheLocation,
                preferencesManager.maxCacheSize,
                preferencesManager.syncCoverArtOnForce,
                preferencesManager.lastSyncTime,
                preferencesManager.serverCapabilities
            ) { flows ->
                PrefsGroup1(
                    serverUrl = flows[0] as String,
                    username = flows[1] as String,
                    password = flows[2] as String,
                    cacheLocation = flows[3] as String,
                    maxCacheSize = flows[4] as Long,
                    syncCoverArtOnForce = flows[5] as Boolean,
                    lastSyncTime = flows[6] as Long,
                    serverCapabilities = flows[7] as de.lwp2070809.speculonic.domain.repository.ServerCapabilities?
                )
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
            
            val playbackGroup = combine(
                preferencesManager.skipSilenceEnabled,
                preferencesManager.duckOnTransientFocusLoss,
                preferencesManager.pauseOnAudioFocusLoss
            ) { skipSilence, duck, pause ->
                PlaybackPrefs(skipSilence, duck, pause)
            }

            val group3 = combine(
                preferencesManager.silentCacheEnabled,
                preferencesManager.playerBackgroundMode,
                bluetoothGroup,
                playbackGroup
            ) { silentCacheEnabled, backgroundMode, bluetooth, playback ->
                PrefsGroup3(silentCacheEnabled, backgroundMode, bluetooth, playback)
            }

            val group4 = combine(
                preferencesManager.trustAllCertificates,
                preferencesManager.showOfflineToast,
                preferencesManager.updateCheckInterval,
                preferencesManager.autoOfflineOnMetered,
                preferencesManager.offlineModeEnabled,
                group3
            ) { flows ->
                PrefsGroup4(
                    trustAllCertificates = flows[0] as Boolean,
                    showOfflineToast = flows[1] as Boolean,
                    updateCheckInterval = flows[2] as de.lwp2070809.speculonic.data.UpdateCheckInterval,
                    autoOfflineOnMetered = flows[3] as Boolean,
                    offlineModeEnabled = flows[4] as Boolean,
                    third = flows[5] as PrefsGroup3
                )
            }

            val statsFlow = combine(
                database.musicDao().getArtistsCountFlow(),
                database.musicDao().getAlbumsCountFlow(),
                database.musicDao().getSongsCountFlow(),
                database.musicDao().getPlaylistsCountFlow()
            ) { artists, albums, songs, playlists ->
                StatsGroup(artists, albums, songs, playlists)
            }

            val syncGroup = combine(
                preferencesManager.isSyncing,
                preferencesManager.syncProgress
            ) { isSyncing, syncProgress ->
                isSyncing to syncProgress
            }

            combine(group1, group2, group4, statsFlow, syncGroup) { g1, g2, g4, stats, sync ->
                { current: SettingsUiState ->
                    current.copy(
                        serverUrl = g1.serverUrl,
                        username = g1.username,
                        password = g1.password,
                        cacheLocation = g1.cacheLocation,
                        maxCacheSize = g1.maxCacheSize,
                        mobilePlayAllowed = g2.mobilePlayAllowed,
                        showOfflineToast = g4.showOfflineToast,
                        backgroundSyncEnabled = g2.backgroundSyncEnabled,
                        logLevel = g2.logLevel,
                        themeMode = g2.themeMode,
                        colorMode = g2.colorMode,
                        playerBackgroundMode = g4.third.backgroundMode,
                        silentCacheEnabled = g4.third.silentCacheEnabled,
                        
                        carBluetoothEnabled = g4.third.bluetooth.carEnabled,
                        syncPlaybackState = g4.third.bluetooth.syncState,
                        skipSilenceEnabled = g4.third.playback.skipSilence,
                        duckOnTransientFocusLoss = g4.third.playback.duckOnTransientFocusLoss,
                        pauseOnAudioFocusLoss = g4.third.playback.pauseOnAudioFocusLoss,

                        bluetoothLyricsEnabled = g4.third.bluetooth.lyricsEnabled,
                        bluetoothLyricsHideProgressBar = g4.third.bluetooth.hideProgress,
                        bluetoothCarDeviceNames = g4.third.bluetooth.deviceNames,
                        
                        language = getCurrentLanguageLabel(),
                        trustAllCertificates = g4.trustAllCertificates,
                        updateCheckInterval = g4.updateCheckInterval,
                        autoOfflineOnMetered = g4.autoOfflineOnMetered,
                        offlineModeEnabled = g4.offlineModeEnabled,
                        isSyncing = sync.first,
                        syncProgress = sync.second,
                        artistsCount = stats.artists,
                        albumsCount = stats.albums,
                        songsCount = stats.songs,
                        playlistsCount = stats.playlists,
                        lastSyncTime = g1.lastSyncTime,
                        syncCoverArtOnForce = g1.syncCoverArtOnForce,
                        serverCapabilities = g1.serverCapabilities
                    )
                }
            }.collect { updater ->
                _uiState.update { updater(it) }
            }
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

    fun updateServerUrl(url: String) { 
        _uiState.value = _uiState.value.copy(serverUrl = url, urlError = null, testConnectionResult = null) 
    }
    fun updateUsername(user: String) { _uiState.value = _uiState.value.copy(username = user, testConnectionResult = null) }
    fun updatePassword(pass: String) { _uiState.value = _uiState.value.copy(password = pass, testConnectionResult = null) }
    
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
                _uiState.value = _uiState.value.copy(testConnectionResult = false to (e.message ?: context.getString(de.lwp2070809.speculonic.R.string.unknown_error)))
            }
            _uiState.value = _uiState.value.copy(isTestingConnection = false)
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

    fun updateTrustAllCertificates(trust: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveTrustAllCertificates(trust)
            
            NetworkModule.rebuildClientIfNeeded(trust)
        }
    }

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
    fun updateSkipSilenceEnabled(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveSkipSilenceEnabled(enabled) } }
    fun updateDuckOnTransientFocusLoss(enabled: Boolean) { viewModelScope.launch { preferencesManager.saveDuckOnTransientFocusLoss(enabled) } }
    fun updatePauseOnAudioFocusLoss(enabled: Boolean) { viewModelScope.launch { preferencesManager.savePauseOnAudioFocusLoss(enabled) } }

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

    fun saveSettings(syncCoverArt: Boolean = false): Boolean {
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
            
            withContext(Dispatchers.IO) {
                try {
                    repository.ping(force = true)
                } catch (e: Exception) {
                    LogManager.w("SettingsViewModel: Ping failed before sync: ${e.message}")
                }
            }
            
            
            if (!repository.hasLocalData() || oldUrl != url || oldUser != user) {
                if (url.isNotEmpty()) {

                    performFullSync(isForced = true, isFromServerSave = true, syncCoverArt = syncCoverArt)
                }
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
        } catch (e: Exception) {
            LogManager.e("Settings: Cover Art Sync Failed", e)
        } finally {
            preferencesManager.saveIsSyncing(false)
            preferencesManager.saveSyncProgress(null)
        }
    }

    fun performFullSync(ignoreSafetyGuard: Boolean = false, isForced: Boolean = false, isFromServerSave: Boolean = false, syncCoverArt: Boolean = false) {
        _uiState.value = _uiState.value.copy(showFirstSyncConfirm = false, showSafetyGuardConfirm = false)
        viewModelScope.launch(Dispatchers.IO) {
            val shouldSyncCovers = repository.isConfigured && 
                (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
            var success = false
            try {
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
            } catch (e: de.lwp2070809.speculonic.domain.repository.SafetyGuardException) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(showSafetyGuardConfirm = true, safetyGuardMessage = e.message)
                }
            } catch (e: Exception) {
                LogManager.e("Settings: Initial sync failed", e)
            } finally {
                val shouldSyncCovers = success && repository.isConfigured && 
                    (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
                if (shouldSyncCovers) {
                    performCoverArtSyncInternal()
                } else {
                    preferencesManager.saveIsSyncing(false)
                    preferencesManager.saveSyncProgress(null)
                }
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
            val breakdown = cacheOperations.calculateCacheSizes(location)
            val freeSpace = try {
                context.cacheDir.freeSpace
            } catch (e: Exception) {
                0L
            }
            val cachedSongs = withContext(Dispatchers.IO) {
                database.musicDao().getAllCachedSongs().size
            }
            val totalInternal = breakdown.playbackBytes + breakdown.coverArtBytes + breakdown.songBytes + breakdown.otherBytes
            _uiState.value = _uiState.value.copy(
                internalCacheSize = cacheOperations.formatFileSize(totalInternal),
                externalCacheSize = cacheOperations.formatFileSize(breakdown.externalBytes),
                internalCacheBytes = totalInternal,
                externalCacheBytes = breakdown.externalBytes,
                freeSpaceBytes = freeSpace,
                cachedSongsCount = cachedSongs,
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

    fun clearPlaybackCache() {
        viewModelScope.launch {
            cacheOperations.clearPlaybackCache()
            refreshCacheSize()
        }
    }

    fun clearCoverArtCache() {
        viewModelScope.launch {
            cacheOperations.clearCoverArtCache()
            refreshCacheSize()
        }
    }

    fun clearSongDownloads() {
        viewModelScope.launch {
            cacheOperations.clearSongDownloads()
            refreshCacheSize()
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

