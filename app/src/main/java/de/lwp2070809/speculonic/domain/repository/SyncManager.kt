package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.SubsonicApiException
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

enum class SyncOutcome {
    SYNCED_FULL,
    SKIPPED_NOT_CONFIGURED,
    SKIPPED_LOCK,
    SKIPPED_RATE_LIMIT,
    SKIPPED_UP_TO_DATE
}

data class SyncOptions(
    val forceRefresh: Boolean = false,
    val ignoreLastModified: Boolean = false,
    val ignoreSafetyGuard: Boolean = false,
    val hasLocalData: Boolean,
    val keepSyncingState: Boolean = false
)

internal data class EntityCountSnapshot(
    val artists: Int,
    val albums: Int,
    val songs: Int
)

internal data class PaginationStep(
    val hasMore: Boolean,
    val nextOffset: Int,
    val offsetBugDetected: Boolean
)

internal data class SyncExecutionParams(
    val ignoreSafetyGuard: Boolean,
    val currentTime: Long,
    val serverLastModified: Long,
    val onProgress: (suspend (String) -> Unit)?,
    val onSyncComplete: suspend (Long, Long) -> Unit
)

class SyncManager(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val pref: PreferencesManager,
    entityMapper: EntityMapper,
    private val onSongsRemoved: suspend (List<SongCleanupTarget>) -> Unit = {},
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val search3Executor = Search3SyncExecutor(
        api = api,
        musicDao = musicDao,
        pref = pref,
        entityMapper = entityMapper,
        json = json,
        runInTransaction = runInTransaction
    )

    private val legacyExecutor = LegacySyncExecutor(
        api = api,
        musicDao = musicDao,
        authManager = authManager,
        pref = pref,
        entityMapper = entityMapper,
        runInTransaction = runInTransaction
    )

    suspend fun syncAllData(
        serverCapabilities: ServerCapabilities,
        options: SyncOptions,
        onProgress: (suspend (String) -> Unit)? = null,
        onSyncComplete: suspend (Long, Long) -> Unit
    ): SyncOutcome {
        if (!syncMutex.tryLock()) {
            LogManager.i("SyncManager: sync is already running, skipping duplicate request.")
            return SyncOutcome.SKIPPED_LOCK
        }
        try {
            val lastSync = pref.lastSyncTime.first()
            val lastServerModified = pref.serverLastModified.first()
            val currentTime = System.currentTimeMillis()

            val hitRateLimit = !options.forceRefresh &&
                (currentTime - lastSync < SYNC_DEBOUNCE_MILLIS) &&
                options.hasLocalData
            if (hitRateLimit) {
                LogManager.d("SyncManager: syncAllData skipped due to rate limiting")
                return SyncOutcome.SKIPPED_RATE_LIMIT
            }

            return executeLockedSync(
                serverCapabilities = serverCapabilities,
                options = options,
                lastServerModified = lastServerModified,
                currentTime = currentTime,
                onProgress = onProgress,
                onSyncComplete = onSyncComplete
            )
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun executeLockedSync(
        serverCapabilities: ServerCapabilities,
        options: SyncOptions,
        lastServerModified: Long,
        currentTime: Long,
        onProgress: (suspend (String) -> Unit)?,
        onSyncComplete: suspend (Long, Long) -> Unit
    ): SyncOutcome {
        try {
            pref.saveIsSyncing(true)
            pref.saveSyncProgress(null)
            pref.saveSyncError(null)
            val authParams = authManager.getAuthParams()
            val progressMsg = "正在检查服务器更新..."
            onProgress?.invoke(progressMsg)
            pref.saveSyncProgress(progressMsg)

            val serverLastModified = fetchServerLastModified(
                authParams = authParams,
                ignoreLastModified = options.ignoreLastModified,
                hasLocalData = options.hasLocalData,
                lastServerModified = lastServerModified
            )

            val skipUnchanged = !options.ignoreLastModified &&
                serverLastModified != 0L &&
                serverLastModified == lastServerModified &&
                options.hasLocalData
            if (skipUnchanged) {
                LogManager.i("SyncManager: Server metadata not changed ($serverLastModified). Skipping deep sync.")
                pref.saveLastSyncTime(currentTime)
                pref.saveSyncProgress(null)
                return SyncOutcome.SKIPPED_UP_TO_DATE
            }

            val executionParams = SyncExecutionParams(
                ignoreSafetyGuard = options.ignoreSafetyGuard,
                currentTime = currentTime,
                serverLastModified = serverLastModified,
                onProgress = onProgress,
                onSyncComplete = onSyncComplete
            )
            if (serverCapabilities.isOpenSubsonic) {
                executeSearch3WithLegacyFallback(authParams, executionParams)
            } else {
                legacyExecutor.execute(executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
            }
            return SyncOutcome.SYNCED_FULL
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                musicDao.clearSyncTempIds()
                musicDao.repairAndCount()
            }.onFailure { cleanupEx ->
                LogManager.e("SyncManager: cleanup/repair failed during sync error handling", cleanupEx)
            }
            pref.saveSyncError(e.message ?: e.toString())
            throw e
        } finally {
            if (!options.keepSyncingState) resetSyncProgress()
        }
    }

    private suspend fun executeSearch3WithLegacyFallback(
        authParams: Triple<String, String, String>,
        executionParams: SyncExecutionParams
    ) {
        try {
            search3Executor.execute(authParams, executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
        } catch (e: Exception) {
            if (!shouldFallbackFromSearch3(e)) throw e
            LogManager.w("SyncManager: search3Stream failed (${e.message}), falling back to legacy sync.")
            runCatching { musicDao.clearSyncTempIds() }
                .onFailure { LogManager.w("SyncManager: clearSyncTempIds failed before legacy fallback: ${it.message}") }
            legacyExecutor.execute(executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
        }
    }

    private fun shouldFallbackFromSearch3(e: Exception): Boolean {
        return when (e) {
            is CancellationException,
            is SafetyGuardException -> false
            is HttpException -> e.code() !in AUTH_HTTP_CODES
            is SubsonicApiException -> e.code !in AUTH_SUBSONIC_ERROR_CODES
            is SerializationException,
            is IllegalStateException -> true
            else -> false
        }
    }

    private suspend fun performSurgicalCleanup(
        updateStatsProgress: suspend (String) -> Unit,
        onProgress: (suspend (String) -> Unit)?
    ) {
        var removalTargets: List<SongCleanupTarget> = emptyList()
        runInTransaction {
            removalTargets = musicDao.getSongsNotInTempForCleanup()
            musicDao.deleteSongsNotInTemp()
            musicDao.deleteAlbumsNotInTemp()
            musicDao.deleteArtistsNotInTemp()
            musicDao.clearSyncTempIds()
            musicDao.repairAndCount()
        }
        if (onProgress != null) {
            updateStatsProgress("正在更新统计信息...")
        }
        onSongsRemoved(removalTargets)
    }

    private suspend fun fetchServerLastModified(
        authParams: Triple<String, String, String>,
        ignoreLastModified: Boolean,
        hasLocalData: Boolean,
        lastServerModified: Long
    ): Long {
        return try {
            val (u, t, s) = authParams
            val ifModifiedSince = if (!ignoreLastModified && hasLocalData) lastServerModified else null
            val indexesResponse = api.getIndexes(u, t, s, ifModifiedSince = ifModifiedSince)
            if (indexesResponse.response.status == "ok") {
                val returnedLastModified = indexesResponse.response.indexes?.lastModified
                returnedLastModified ?: if (ifModifiedSince != null) lastServerModified else 0L
            } else {
                0L
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.w("SyncManager: Failed to get lastModified from getIndexes - ${e.message}")
            0L
        }
    }

    private fun verifySafetyGuard(
        local: EntityCountSnapshot,
        remote: EntityCountSnapshot,
        ignoreSafetyGuard: Boolean,
        isLegacy: Boolean = false
    ) {
        if (ignoreSafetyGuard) return
        val modePrefix = if (isLegacy) "传统模式" else "全量同步"
        checkDimensionThreshold(local.songs, remote.songs, "歌曲", modePrefix)
        checkDimensionThreshold(local.albums, remote.albums, "专辑", modePrefix)
        checkDimensionThreshold(local.artists, remote.artists, "艺术家", modePrefix)
    }

    private fun checkDimensionThreshold(localCount: Int, remoteCount: Int, label: String, modePrefix: String) {
        if (localCount < MIN_ENTITIES_FOR_SAFETY) return
        val dropThreshold = localCount * SAFETY_GUARD_DROP_RATIO
        if (remoteCount < dropThreshold) {
            throw SafetyGuardException(
                "安全保护触发：${modePrefix}返回${label}数 ($remoteCount) 远低于本地基数 ($localCount)。同步已中止以防止误删。"
            )
        }
    }

    suspend fun quickSync(
        onProgress: (suspend (String) -> Unit)? = null,
        onComplete: suspend (Long) -> Unit
    ) {
        if (!syncMutex.tryLock()) {
            LogManager.i("SyncManager: sync is already running, skipping quickSync.")
            return
        }
        try {
            pref.saveIsSyncing(true)
            val currentTime = System.currentTimeMillis()
            onProgress?.invoke("正在执行轻量同步...")
            legacyExecutor.syncAllAlbums(type = "newest")
            musicDao.repairAndCount()
            onComplete(currentTime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("SyncManager: quickSync failed", e)
            throw e
        } finally {
            try {
                resetSyncProgress()
            } finally {
                syncMutex.unlock()
            }
        }
    }

    private suspend fun resetSyncProgress() = withContext(NonCancellable) {
        pref.saveIsSyncing(false)
        pref.saveSyncProgress(null)
    }

    companion object {
        private const val SYNC_DEBOUNCE_MILLIS = 12 * 60 * 60 * 1000L
        private const val SAFETY_GUARD_DROP_RATIO = 0.7
        private const val MIN_ENTITIES_FOR_SAFETY = 10
        private val AUTH_HTTP_CODES = setOf(401)
        private val AUTH_SUBSONIC_ERROR_CODES = setOf(40, 41, 50)
    }
}
