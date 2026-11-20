package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.repository.SafetyGuardException
import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.domain.usecase.TestConnectionUseCase
import de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URISyntaxException
import java.net.URL
import javax.inject.Inject

private const val COVER_PROGRESS_THROTTLE_MS = 300L
private const val SYNC_STOP_TIMEOUT_MS = 5000L

data class ServerSyncUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowInsecureConnections: Boolean = false,
    val credentialsResetWarning: Boolean = false,
    val serverCapabilities: ServerCapabilities? = null,
    val backgroundSyncEnabled: Boolean = true,
    val syncCoverArtOnForce: Boolean = false,
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val testConnectionResult: Pair<Boolean, String?>? = null,
    val urlError: String? = null,
    val isSyncing: Boolean = false,
    val syncProgress: String? = null,
    val syncError: String? = null,
    val lastSyncTime: Long = 0L,
    val artistsCount: Int = 0,
    val albumsCount: Int = 0,
    val songsCount: Int = 0,
    val playlistsCount: Int = 0,
    val showFirstSyncConfirm: Boolean = false,
    val showSafetyGuardConfirm: Boolean = false,
    val safetyGuardMessage: String? = null,
    val showForceSyncConfirm: Boolean = false
)

@HiltViewModel
class ServerSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val repository: SubsonicRepository,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val database: AppDatabase,
    private val cacheOperations: CacheOperations
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSyncUiState())
    val uiState: StateFlow<ServerSyncUiState> = _uiState.asStateFlow()

    val isSyncing: StateFlow<Boolean> = preferencesManager.isSyncing.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SYNC_STOP_TIMEOUT_MS),
        initialValue = false
    )

    private var syncJob: Job? = null
    private var testConnectionJob: Job? = null

    init {
        observeServerConnectionPrefs()
        observeSyncPrefs()
        observeLibraryCounts()
    }

    private fun observeServerConnectionPrefs() {
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
    }

    private fun observeSyncPrefs() {
        viewModelScope.launch {
            combine(
                preferencesManager.backgroundSyncEnabled,
                preferencesManager.syncCoverArtOnForce
            ) { bgSync, syncCover ->
                _uiState.update { current ->
                    current.copy(
                        backgroundSyncEnabled = bgSync,
                        syncCoverArtOnForce = syncCover
                    )
                }
            }.collect()
        }
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
    }

    private fun observeLibraryCounts() {
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
        val hasSupportedScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        if (trimmed.isEmpty() || !hasSupportedScheme) return false
        return try {
            URL(trimmed).toURI() != null
        } catch (e: IOException) {
            LogManager.d("Invalid server URL: $trimmed (${e.message})")
            false
        } catch (e: URISyntaxException) {
            LogManager.d("Invalid server URI: $trimmed (${e.message})")
            false
        } catch (e: IllegalArgumentException) {
            LogManager.d("Invalid server URL argument: $trimmed (${e.message})")
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
                    urlError = context.getString(R.string.error_invalid_url),
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
                            testConnectionResult = false to (e.message ?: context.getString(R.string.unknown_error))
                        )
                    }
                }
            } finally {
                _uiState.update { it.copy(isTestingConnection = false) }
            }
        }
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
            _uiState.update { it.copy(urlError = context.getString(R.string.error_invalid_url)) }
            return false
        }

        testConnectionJob?.cancel()
        testConnectionJob = null
        viewModelScope.launch {
            persistAndApplyServerSettings(url, user, pass, syncCoverArt, allowInsecureConnections)
        }
        return true
    }

    private suspend fun persistAndApplyServerSettings(
        url: String,
        user: String,
        pass: String,
        syncCoverArt: Boolean,
        allowInsecureConnections: Boolean
    ) {
        _uiState.update { it.copy(isSaving = true, isTestingConnection = false, urlError = null, testConnectionResult = null) }
        try {
            val (oldUrl, oldUser) = preferencesManager.readServerIdentity()
            val isServerChanged = oldUrl.isNotEmpty() && (url != oldUrl || user != oldUser)

            withContext(Dispatchers.IO) {
                preferencesManager.saveAllowInsecureConnections(allowInsecureConnections)
                applyNetworkTransition(isServerChanged, allowInsecureConnections)
                NetworkModule.ServerReachableManager.reset()
            }

            preferencesManager.saveServerSettings(url, user, pass)
            _uiState.update {
                it.copy(
                    serverUrl = url,
                    username = user,
                    password = pass,
                    allowInsecureConnections = allowInsecureConnections
                )
            }
            pingAndTriggerInitialSyncIfNeeded(url, oldUrl, user, oldUser, syncCoverArt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("ServerSyncViewModel: Failed saving server settings", e)
            val detail = e.localizedMessage ?: context.getString(R.string.unknown_error)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.server_save_retry, detail), Toast.LENGTH_LONG).show()
            }
        } finally {
            _uiState.update { it.copy(isSaving = false) }
        }
    }

    private suspend fun applyNetworkTransition(isServerChanged: Boolean, allowInsecureConnections: Boolean) {
        if (isServerChanged) {
            // 换服时先取消后台同步循环、应用新安全策略并驱逐连接池，避免旧服同步继续发起请求或写入即将清空的库表
            stopSyncAndClearConnections(allowInsecureConnections)
            NetworkModule.clearSubsonicServiceCache()
            database.clearAllTables()
            cacheOperations.clearAllCache(preserveSaf = false).onFailure { e ->
                LogManager.e("ServerSyncViewModel: clearAllCache failed during server change", e)
            }
        } else if (DynamicSslTrustManager.allowInsecureConnections && !allowInsecureConnections) {
            // 安全降级时先取消在途同步再驱逐连接池，避免先断连触发异步异常并重复驱逐
            stopSyncAndClearConnections(allowInsecureConnections)
        } else {
            NetworkModule.rebuildClientIfNeeded(allowInsecureConnections)
        }
    }

    private suspend fun pingAndTriggerInitialSyncIfNeeded(
        url: String,
        oldUrl: String,
        user: String,
        oldUser: String,
        syncCoverArt: Boolean
    ) {
        withContext(Dispatchers.IO) {
            try {
                repository.ping(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("ServerSyncViewModel: Ping failed before sync: ${e.message}")
            }
        }

        if (!repository.hasLocalData() || oldUrl != url || oldUser != user) {
            if (url.isNotEmpty()) {
                performFullSync(isForced = true, syncCoverArt = syncCoverArt)
            }
        }
    }

    fun updateBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveBackgroundSyncEnabled(enabled) }
    }

    fun updateSyncCoverArtOnForce(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveSyncCoverArtOnForce(enabled) }
    }

    fun cancelFirstSync() {
        _uiState.update { it.copy(showFirstSyncConfirm = false) }
    }

    fun requestForceSync() {
        _uiState.update { it.copy(showForceSyncConfirm = true) }
    }

    fun cancelForceSync() {
        _uiState.update { it.copy(showForceSyncConfirm = false) }
    }

    fun confirmForceSync() {
        _uiState.update { it.copy(showForceSyncConfirm = false) }
        performFullSync(isForced = true)
    }

    fun confirmSafetyGuard() {
        performFullSync(ignoreSafetyGuard = true, isForced = true)
    }

    fun cancelSafetyGuard() {
        _uiState.update { it.copy(showSafetyGuardConfirm = false, safetyGuardMessage = null) }
    }

    private suspend fun performCoverArtSyncInternal() {
        try {
            preferencesManager.saveIsSyncing(true)
            preferencesManager.saveSyncProgress(context.getString(R.string.sync_cover_art_preparing))
            var lastNotifyTime = 0L
            repository.syncAllCoverArt(onProgress = { status ->
                val now = System.currentTimeMillis()
                if (now - lastNotifyTime >= COVER_PROGRESS_THROTTLE_MS) {
                    lastNotifyTime = now
                    preferencesManager.saveSyncProgress(status)
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("ServerSyncViewModel: Cover Art Sync Failed", e)
        } finally {
            withContext(NonCancellable) {
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
            val shouldKeepSyncStateForCovers = repository.isConfigured &&
                (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
            var success = false
            try {
                preferencesManager.saveSyncError(null)
                preferencesManager.saveIsSyncing(true)
                preferencesManager.saveSyncProgress(context.getString(R.string.sync_preparing))
                syncAllDataUseCase(
                    forceRefresh = isForced,
                    ignoreLastModified = isForced,
                    ignoreSafetyGuard = ignoreSafetyGuard,
                    keepSyncingState = shouldKeepSyncStateForCovers,
                    onProgress = { status ->
                        preferencesManager.saveSyncProgress(status)
                    }
                )
                success = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafetyGuardException) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(showSafetyGuardConfirm = true, safetyGuardMessage = e.message) }
                }
            } catch (e: Exception) {
                LogManager.e("ServerSyncViewModel: Initial sync failed", e)
                preferencesManager.saveSyncError(e.message ?: e.toString())
            } finally {
                val runCoverSync = success && repository.isConfigured &&
                    (syncCoverArt || (isForced && preferencesManager.syncCoverArtOnForce.first()))
                if (runCoverSync) {
                    performCoverArtSyncInternal()
                } else {
                    withContext(NonCancellable) {
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

    fun deleteServerSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先于清除凭据终止同步任务与在途请求、驱逐连接池并重置不安全连接策略，防止删除后仍有旧会话复用
                stopSyncAndClearConnections(allowInsecureConnections = false)
                NetworkModule.clearSubsonicServiceCache()
                preferencesManager.saveAllowInsecureConnections(false)
                preferencesManager.saveServerSettings("", "", "")

                database.clearAllTables()
                cacheOperations.clearAllCache(preserveSaf = false).onFailure { e ->
                    LogManager.e("ServerSyncViewModel: clearAllCache failed during server deletion", e)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e("ServerSyncViewModel: Failed deleting server settings", e)
                val detail = e.localizedMessage ?: context.getString(R.string.unknown_error)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
