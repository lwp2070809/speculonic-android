package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.dao.SQLITE_IN_CHUNK_SIZE
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.SubsonicApiException
import de.lwp2070809.speculonic.network.model.ensureOk
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
            legacyExecutor.execute(executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
        }
    }

    private fun shouldFallbackFromSearch3(e: Exception): Boolean {
        return when (e) {
            is CancellationException,
            is SafetyGuardException -> false
            is HttpException -> e.code() !in AUTH_HTTP_CODES
            is SubsonicApiException -> e.code !in AUTH_SUBSONIC_ERROR_CODES
            is IncompletePaginationException,
            is SerializationException,
            is IllegalStateException -> true
            else -> false
        }
    }

    private suspend fun performSurgicalCleanup(
        updateStatsProgress: suspend (String) -> Unit,
        onProgress: (suspend (String) -> Unit)?
    ) {
        // Offset-based enumeration can miss an existing item if the library changes between requests.
        // Complete all independent lookups before committing any deletions or purging files.
        val removedByType = confirmRemoteRemovals().groupBy({ it.type }, { it.id })
        val removalTargets = mutableListOf<SongCleanupTarget>()
        runInTransaction {
            removedByType["song"].orEmpty().chunked(SQLITE_IN_CHUNK_SIZE).forEach { ids ->
                val targets = musicDao.getConfirmedSongCleanupTargets(ids)
                musicDao.deleteSongsByIds(targets.map { it.id })
                removalTargets.addAll(targets)
            }
            removedByType["album"].orEmpty().chunked(SQLITE_IN_CHUNK_SIZE).forEach { musicDao.deleteConfirmedAlbums(it) }
            removedByType["artist"].orEmpty().chunked(SQLITE_IN_CHUNK_SIZE).forEach { musicDao.deleteConfirmedArtists(it) }
            musicDao.clearSyncTempIds()
            musicDao.repairAndCount()
        }
        if (onProgress != null) {
            updateStatsProgress("正在更新统计信息...")
        }
        if (removalTargets.isNotEmpty()) onSongsRemoved(removalTargets)
    }

    private suspend fun confirmRemoteRemovals(): List<SyncTempIdEntity> {
        val candidates = musicDao.getUnseenEntities()
        if (candidates.isEmpty()) return emptyList()
        val auth = authManager.getAuthParams()
        return candidates.chunked(LOOKUP_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { async { confirmRemoteRemoval(it, auth) } }.awaitAll().filterNotNull()
            }
        }
    }

    private suspend fun confirmRemoteRemoval(candidate: SyncTempIdEntity, auth: Triple<String, String, String>): SyncTempIdEntity? {
        val (user, token, salt) = auth
        val response = when (candidate.type) {
            "song" -> api.getSong(candidate.id, user, token, salt).response
            "album" -> api.getAlbum(candidate.id, user, token, salt).response
            "artist" -> api.getArtist(candidate.id, user, token, salt).response
            else -> error("Unknown library entity: ${candidate.type}")
        }
        if (response.status == "failed" && response.error?.code == RESOURCE_NOT_FOUND) return candidate
        response.ensureOk()
        return null
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
        checkDimensionThreshold(local.songs, remote.songs, SafetyGuardException.Entity.SONGS, isLegacy)
        checkDimensionThreshold(local.albums, remote.albums, SafetyGuardException.Entity.ALBUMS, isLegacy)
        checkDimensionThreshold(local.artists, remote.artists, SafetyGuardException.Entity.ARTISTS, isLegacy)
    }

    private fun checkDimensionThreshold(
        localCount: Int,
        remoteCount: Int,
        entity: SafetyGuardException.Entity,
        isLegacy: Boolean
    ) {
        if (localCount < MIN_ENTITIES_FOR_SAFETY) return
        val dropThreshold = localCount * SAFETY_GUARD_DROP_RATIO
        if (remoteCount < dropThreshold) {
            throw SafetyGuardException(entity, remoteCount, localCount, isLegacy)
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
        private const val LOOKUP_CONCURRENCY = 8
        private const val RESOURCE_NOT_FOUND = 70
        private val AUTH_HTTP_CODES = setOf(401)
        private val AUTH_SUBSONIC_ERROR_CODES = setOf(40, 41, 50)
    }
}
