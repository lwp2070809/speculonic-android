package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheSyncWorker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.SafetyGuardException
import de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase
import de.lwp2070809.speculonic.domain.usecase.SyncAllDataUseCase
import de.lwp2070809.speculonic.domain.usecase.VerifyCacheConsistencyUseCase
import de.lwp2070809.speculonic.domain.usecase.VerifyCacheState
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

private const val DEFAULT_MAX_COVER_CACHE_BYTES = 512L * 1024 * 1024

enum class CacheClearTarget {
    ALL,
    PLAYBACK,
    COVER_ART,
    SONGS
}

enum class StorageErrorSource {
    SCAN,
    VERIFY
}

data class StorageErrorDialogState(
    val source: StorageErrorSource,
    val message: String,
    val showConfigureSafAction: Boolean
)

data class StorageCacheUiState(
    val cacheLocation: String = "",
    val maxCoverCacheSize: Long = DEFAULT_MAX_COVER_CACHE_BYTES,
    val isCoverOverQuota: Boolean = false,
    val silentCacheEnabled: Boolean = true,
    val internalCacheSize: String = "0 B",
    val externalCacheSize: String = "0 B",
    val internalCacheBytes: Long = 0L,
    val externalCacheBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val totalSpaceBytes: Long = 0L,
    val cachedSongsCount: Int = 0,
    val playbackCacheBytes: Long = 0L,
    val coverArtCacheBytes: Long = 0L,
    val songCacheBytes: Long = 0L,
    val otherCacheBytes: Long = 0L,
    val playbackCacheSize: String = "0 B",
    val coverArtCacheSize: String = "0 B",
    val songCacheSize: String = "0 B",
    val otherCacheSize: String = "0 B",
    val isScanning: Boolean = false,
    val syncProgress: String? = null,
    val syncPercentage: Int? = null,
    val isInteractiveScanning: Boolean = false,
    val interactiveScanProgress: Int = 0,
    val interactiveScanStatus: String = "",
    val storageError: StorageErrorDialogState? = null,
    val showInconsistencyDialog: Boolean = false,
    val inconsistentItems: List<InconsistentItem> = emptyList(),
    val pendingClearCacheTarget: CacheClearTarget? = null,
    val showSilentCacheConfirm: Boolean = false,
    val showMobileSyncConfirm: Boolean = false,
    val showSafetyGuardConfirm: Boolean = false,
    val safetyGuardMessage: String? = null
)

@HiltViewModel
class StorageCacheViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val playbackController: PlaybackController,
    private val syncAllDataUseCase: SyncAllDataUseCase,
    private val verifyCacheConsistencyUseCase: VerifyCacheConsistencyUseCase,
    private val resolveInconsistencyUseCase: ResolveInconsistencyUseCase,
    private val database: AppDatabase,
    private val cacheOperations: CacheOperations
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageCacheUiState())
    val uiState: StateFlow<StorageCacheUiState> = _uiState.asStateFlow()
    private val pendingScanWorkId = AtomicReference<UUID?>(null)

    init {
        observeStoragePreferences()
        observeWorkProgress()
    }

    private fun observeStoragePreferences() {
        viewModelScope.launch {
            combine(
                preferencesManager.cacheLocation,
                preferencesManager.maxCoverCacheSize,
                preferencesManager.silentCacheEnabled
            ) { loc, maxCover, silentCache ->
                _uiState.update { current ->
                    current.copy(
                        cacheLocation = loc,
                        maxCoverCacheSize = maxCover,
                        silentCacheEnabled = silentCache
                    )
                }
            }.collect()
        }
    }

    fun updateCacheLocation(location: String) {
        viewModelScope.launch {
            preferencesManager.saveCacheLocation(location)
            if (location.isNotEmpty()) {
                scanLocalFiles(replaceExisting = true)
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

    fun updateMaxCoverCacheSize(size: Long) {
        viewModelScope.launch { preferencesManager.saveMaxCoverCacheSize(size) }
    }

    fun updateSilentCacheEnabled(enabled: Boolean) {
        if (!enabled) {
            _uiState.update { it.copy(showSilentCacheConfirm = true) }
        } else {
            viewModelScope.launch { preferencesManager.saveSilentCacheEnabled(true) }
        }
    }

    fun confirmDisableSilentCache() {
        viewModelScope.launch {
            preferencesManager.saveSilentCacheEnabled(false)
            _uiState.update { it.copy(showSilentCacheConfirm = false) }
        }
    }

    fun cancelDisableSilentCache() {
        _uiState.update { it.copy(showSilentCacheConfirm = false) }
    }

    fun requestClearCache(target: CacheClearTarget = CacheClearTarget.ALL) {
        _uiState.update { it.copy(pendingClearCacheTarget = target) }
    }

    fun cancelClearCache() {
        _uiState.update { it.copy(pendingClearCacheTarget = null) }
    }

    fun confirmClearCache() {
        val target = _uiState.value.pendingClearCacheTarget ?: return
        _uiState.update { it.copy(pendingClearCacheTarget = null) }
        when (target) {
            CacheClearTarget.ALL -> clearCache()
            CacheClearTarget.PLAYBACK -> clearPlaybackCache()
            CacheClearTarget.COVER_ART -> clearCoverArtCache()
            CacheClearTarget.SONGS -> clearSongDownloads()
        }
    }

    private fun clearCache() {
        _uiState.update { it.copy(pendingClearCacheTarget = null) }
        viewModelScope.launch {
            cacheOperations.clearAllCache()
                .onSuccess {
                    LogManager.i("StorageCacheViewModel: Internal cache cleared.")
                    refreshCacheSize()
                    scanLocalFiles(replaceExisting = true)
                }
                .onFailure { e ->
                    showCacheOperationFailureToast(e)
                }
        }
    }

    private fun clearPlaybackCache() {
        viewModelScope.launch {
            cacheOperations.clearPlaybackCache()
                .onSuccess { refreshCacheSize() }
                .onFailure { e -> showCacheOperationFailureToast(e) }
        }
    }

    private fun clearCoverArtCache() {
        viewModelScope.launch {
            cacheOperations.clearCoverArtCache()
                .onSuccess { refreshCacheSize() }
                .onFailure { e -> showCacheOperationFailureToast(e) }
        }
    }

    private fun clearSongDownloads() {
        viewModelScope.launch {
            cacheOperations.clearSongDownloads()
                .onSuccess { refreshCacheSize() }
                .onFailure { e -> showCacheOperationFailureToast(e) }
        }
    }

    fun requestSyncWithServer() {
        if (isUnmeteredNetwork()) syncWithServer()
        else _uiState.update { it.copy(showMobileSyncConfirm = true) }
    }

    fun cancelMobileSync() {
        _uiState.update { it.copy(showMobileSyncConfirm = false) }
    }

    fun confirmSafetyGuard() {
        syncWithServer(ignoreSafetyGuard = true)
    }

    fun cancelSafetyGuard() {
        _uiState.update { it.copy(showSafetyGuardConfirm = false, safetyGuardMessage = null) }
    }

    fun syncWithServer(ignoreSafetyGuard: Boolean = false) {
        _uiState.update { it.copy(showMobileSyncConfirm = false, showSafetyGuardConfirm = false) }
        if (playbackController.playbackState.value.showPauseButton) {
            playbackController.togglePlayPause()
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInteractiveScanning = true,
                    interactiveScanProgress = 0,
                    storageError = null,
                    interactiveScanStatus = context.getString(R.string.sync_preparing),
                    showInconsistencyDialog = false,
                    inconsistentItems = emptyList()
                )
            }

            if (!runLightweightServerSync(ignoreSafetyGuard)) {
                return@launch
            }
            collectCacheConsistencyCheck()
        }
    }

    private suspend fun runLightweightServerSync(ignoreSafetyGuard: Boolean): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                syncAllDataUseCase(
                    forceRefresh = false,
                    ignoreLastModified = false,
                    ignoreSafetyGuard = ignoreSafetyGuard,
                    onProgress = { status ->
                        _uiState.update { it.copy(interactiveScanStatus = status) }
                    }
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SafetyGuardException) {
            _uiState.update {
                it.copy(
                    isInteractiveScanning = false,
                    showSafetyGuardConfirm = true,
                    safetyGuardMessage = e.toUserMessage(context)
                )
            }
            false
        } catch (e: Exception) {
            LogManager.e("StorageCacheViewModel: Lightweight sync failed before cache check", e)
            true
        }
    }

    private suspend fun collectCacheConsistencyCheck() {
        val cacheLocation = preferencesManager.cacheLocation.first()
        verifyCacheConsistencyUseCase(cacheLocation).collect { state ->
            when (state) {
                is VerifyCacheState.Progress -> {
                    _uiState.update {
                        it.copy(
                            isInteractiveScanning = true,
                            interactiveScanProgress = state.percentage,
                            interactiveScanStatus = state.status
                        )
                    }
                }
                is VerifyCacheState.Success -> {
                    _uiState.update {
                        it.copy(
                            isInteractiveScanning = false,
                            showInconsistencyDialog = true,
                            inconsistentItems = state.inconsistentItems
                        )
                    }
                }
                is VerifyCacheState.Error -> {
                    val showSafAction = cacheLocation.isNotBlank() &&
                        (state.exception is SecurityException || state.exception is IOException)
                    val message = if (state.exception is SecurityException) {
                        context.getString(R.string.saf_permission_expired)
                    } else {
                        state.exception.localizedMessage ?: state.exception.javaClass.simpleName
                    }
                    _uiState.update {
                        it.copy(
                            isInteractiveScanning = false,
                            showInconsistencyDialog = false,
                            storageError = StorageErrorDialogState(
                                source = StorageErrorSource.VERIFY,
                                message = message,
                                showConfigureSafAction = showSafAction
                            ),
                            inconsistentItems = emptyList()
                        )
                    }
                }
            }
        }
    }

    fun dismissInconsistencyDialog() {
        _uiState.update { it.copy(showInconsistencyDialog = false, storageError = null, inconsistentItems = emptyList()) }
    }

    fun resolveInconsistentItem(item: InconsistentItem, action: String) {
        viewModelScope.launch {
            val useCaseAction = if (action == "DELETE") {
                ResolveInconsistencyUseCase.Action.DELETE
            } else {
                ResolveInconsistencyUseCase.Action.REDOWNLOAD
            }
            val result = resolveInconsistencyUseCase(item, useCaseAction)
            if (result.isSuccess) {
                _uiState.update { state ->
                    state.copy(inconsistentItems = state.inconsistentItems.filter { it.id != item.id })
                }
                if (useCaseAction == ResolveInconsistencyUseCase.Action.REDOWNLOAD) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.redownload_queued, item.displayTitle),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun scanLocalFiles(replaceExisting: Boolean = false) {
        if (playbackController.playbackState.value.showPauseButton) {
            playbackController.togglePlayPause()
        }

        val workId = CacheSyncWorker.runOnce(
            context,
            forceScan = true,
            healCovers = false,
            replaceExisting = replaceExisting
        )
        if (replaceExisting) {
            pendingScanWorkId.set(workId)
        } else if (!_uiState.value.isScanning) {
            pendingScanWorkId.compareAndSet(null, workId)
        }
    }

    private fun observeWorkProgress() {
        viewModelScope.launch {
            var observedActiveId: UUID? = null
            var lastConsumedFinishedId: UUID? = null
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(CacheSyncWorker.MANUAL_SCAN_WORK_NAME)
                .collect { infos ->
                    val active = infos.firstOrNull { !it.state.isFinished }
                    val pendingId = pendingScanWorkId.get()
                    val finished = infos.firstOrNull { info ->
                        isConsumableFinishedScan(info, observedActiveId, pendingId, lastConsumedFinishedId)
                    }
                    observedActiveId = active?.id
                    if (active != null && active.id != pendingId) {
                        pendingScanWorkId.compareAndSet(pendingId, null)
                    }
                    updateScanningProgress(active)
                    if (finished != null) {
                        lastConsumedFinishedId = finished.id
                        pendingScanWorkId.compareAndSet(finished.id, null)
                        handleFinishedScan(finished)
                    }
                }
        }
    }

    private fun isConsumableFinishedScan(
        info: WorkInfo,
        observedActiveId: UUID?,
        pendingId: UUID?,
        lastConsumedFinishedId: UUID?
    ): Boolean {
        if (!info.state.isFinished || info.state == WorkInfo.State.CANCELLED) return false
        if (info.id == lastConsumedFinishedId) return false
        return info.id == observedActiveId || info.id == pendingId
    }

    private fun updateScanningProgress(active: WorkInfo?) {
        _uiState.update {
            it.copy(
                isScanning = active != null,
                syncPercentage = active?.progress?.getInt(CacheSyncWorker.PROGRESS, 0),
                syncProgress = active?.let { work ->
                    work.progress.getString(CacheSyncWorker.STATUS) ?: context.getString(R.string.stop_and_scan)
                }
            )
        }
    }

    private fun handleFinishedScan(finished: WorkInfo) {
        finished.outputData.getString(CacheSyncWorker.STATUS)?.let { message ->
            val needsSaf = finished.outputData.getBoolean(CacheSyncWorker.KEY_ERROR_NEEDS_SAF, false)
            _uiState.update {
                it.copy(
                    storageError = StorageErrorDialogState(
                        source = StorageErrorSource.SCAN,
                        message = message,
                        showConfigureSafAction = needsSaf
                    )
                )
            }
        }
        refreshCacheSize()
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
                val free = try {
                    context.cacheDir.freeSpace
                } catch (e: SecurityException) {
                    LogManager.w("StorageCacheViewModel: failed to read freeSpace (${e.message})")
                    0L
                }
                val total = try {
                    context.cacheDir.totalSpace
                } catch (e: SecurityException) {
                    LogManager.w("StorageCacheViewModel: failed to read totalSpace (${e.message})")
                    0L
                }
                val songsCount = database.musicDao().getCachedSongsCount()
                Triple(free, total, songsCount)
            }
            val totalInternal = breakdown.playbackBytes + breakdown.coverArtBytes + breakdown.songBytes + breakdown.otherBytes
            _uiState.update { state ->
                val isCoverOverQuota = breakdown.coverArtBytes > state.maxCoverCacheSize
                state.copy(
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
    }

    private fun showCacheOperationFailureToast(error: Throwable) {
        viewModelScope.launch(Dispatchers.Main) {
            val msg = error.message ?: error.toString()
            Toast.makeText(context, context.getString(R.string.clear_cache_failed, msg), Toast.LENGTH_SHORT).show()
        }
    }
}
