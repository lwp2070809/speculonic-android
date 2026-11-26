package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.dao.SQLITE_IN_CHUNK_SIZE
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.interceptor.ServerUnavailableException
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
import java.io.IOException

enum class SyncOutcome {
    SYNCED_FULL,
    SYNCED_PARTIAL_CLEANUP,
    SKIPPED_NOT_CONFIGURED,
    SKIPPED_LOCK,
    SKIPPED_RATE_LIMIT,
    SKIPPED_UP_TO_DATE
}

data class SyncOptions(
    val forceRefresh: Boolean = false,
    val ignoreLastModified: Boolean = false,
    val ignoreDropRatioGuard: Boolean = false,
    val skipRemoteVerification: Boolean = false,
    val hasLocalData: Boolean,
    val keepSyncingState: Boolean = false
)

internal data class EntityCountSnapshot(
    val artists: Int,
    val albums: Int,
    val songs: Int
)

internal data class CleanupContext(
    val skipRemoteVerification: Boolean,
    val localSnapshot: EntityCountSnapshot,
    val remoteCounts: EntityCountSnapshot
)

internal data class CleanupResult(
    val hasVerificationFailures: Boolean
)

internal data class SyncExecutionParams(
    val ignoreDropRatioGuard: Boolean,
    val skipRemoteVerification: Boolean,
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

    private val albumListHelper = AlbumListSyncHelper(
        api = api,
        musicDao = musicDao,
        authManager = authManager,
        entityMapper = entityMapper,
        runInTransaction = runInTransaction
    )

    private val search3Executor = Search3SyncExecutor(
        api = api,
        musicDao = musicDao,
        pref = pref,
        entityMapper = entityMapper,
        json = json,
        runInTransaction = runInTransaction
    )

    private val id3Executor = Id3SyncExecutor(
        api = api,
        musicDao = musicDao,
        authManager = authManager,
        pref = pref,
        entityMapper = entityMapper,
        albumListHelper = albumListHelper,
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
                ignoreDropRatioGuard = options.ignoreDropRatioGuard,
                skipRemoteVerification = options.skipRemoteVerification,
                currentTime = currentTime,
                serverLastModified = serverLastModified,
                onProgress = onProgress,
                onSyncComplete = onSyncComplete
            )
            return if (serverCapabilities.isOpenSubsonic) {
                executeSearch3WithId3Fallback(authParams, executionParams)
            } else {
                id3Executor.execute(executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
            }
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

    private suspend fun executeSearch3WithId3Fallback(
        authParams: Triple<String, String, String>,
        executionParams: SyncExecutionParams
    ): SyncOutcome {
        return try {
            search3Executor.execute(authParams, executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
        } catch (e: Exception) {
            if (!shouldFallbackFromSearch3(e)) throw e
            LogManager.w("SyncManager: search3Stream failed (${e.message}), falling back to ID3 sync.")
            id3Executor.execute(executionParams, ::verifySafetyGuard, ::performSurgicalCleanup)
        }
    }

    private fun shouldFallbackFromSearch3(e: Exception): Boolean {
        return when (e) {
            is CancellationException,
            is SafetyGuardException,
            is ServerUnavailableException -> false
            is HttpException,
            is SubsonicApiException -> !isAuthError(e)
            is IncompletePaginationException,
            is SerializationException,
            is IllegalStateException -> true
            else -> false
        }
    }

    private data class RemovalResolution(
        val removals: List<SyncTempIdEntity>,
        val hasVerificationFailures: Boolean
    )

    private sealed interface VerificationOutcome {
        object ConfirmedRemoved : VerificationOutcome
        object StillExists : VerificationOutcome
        object TransientFailure : VerificationOutcome
    }

    private suspend fun performSurgicalCleanup(
        cleanupContext: CleanupContext,
        updateStatsProgress: suspend (String) -> Unit,
        onProgress: (suspend (String) -> Unit)?
    ): CleanupResult {
        val candidates = musicDao.getUnseenEntities()
        val resolution = if (candidates.isEmpty()) {
            RemovalResolution(emptyList(), hasVerificationFailures = false)
        } else {
            resolveRemovals(candidates, cleanupContext)
        }
        val removedByType = resolution.removals.groupBy({ it.type }, { it.id })
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
        return CleanupResult(hasVerificationFailures = resolution.hasVerificationFailures)
    }

    private suspend fun resolveRemovals(
        candidates: List<SyncTempIdEntity>,
        cleanupContext: CleanupContext
    ): RemovalResolution {
        if (candidates.size > MAX_REMOTE_VERIFY_CANDIDATES) {
            if (cleanupContext.skipRemoteVerification) {
                LogManager.w(
                    "SyncManager: Skipping remote verification for ${candidates.size} candidates " +
                        "(skipRemoteVerification=true); purging diff directly."
                )
                return RemovalResolution(candidates, hasVerificationFailures = false)
            }
            val dominantType = candidates.groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key
            val (entity, localCount, remoteCount) = when (dominantType) {
                "album" -> Triple(
                    SafetyGuardException.Entity.ALBUMS,
                    cleanupContext.localSnapshot.albums,
                    cleanupContext.remoteCounts.albums
                )
                "artist" -> Triple(
                    SafetyGuardException.Entity.ARTISTS,
                    cleanupContext.localSnapshot.artists,
                    cleanupContext.remoteCounts.artists
                )
                else -> Triple(
                    SafetyGuardException.Entity.SONGS,
                    cleanupContext.localSnapshot.songs,
                    cleanupContext.remoteCounts.songs
                )
            }
            throw SafetyGuardException(
                entity = entity,
                remoteCount = remoteCount,
                localCount = localCount,
                reason = SafetyGuardException.Reason.VERIFY_CANDIDATES_EXCEEDED,
                candidateCount = candidates.size
            )
        }
        return confirmRemoteRemovals(candidates)
    }

    private suspend fun confirmRemoteRemovals(candidates: List<SyncTempIdEntity>): RemovalResolution {
        val auth = authManager.getAuthParams()
        val confirmed = mutableListOf<SyncTempIdEntity>()
        var consecutiveFailures = 0
        var hasFailures = false

        for (batch in candidates.chunked(LOOKUP_CONCURRENCY)) {
            val outcomes = coroutineScope {
                batch.map { candidate ->
                    async { candidate to confirmRemoteRemoval(candidate, auth) }
                }.awaitAll()
            }
            for ((candidate, outcome) in outcomes) {
                when (outcome) {
                    VerificationOutcome.ConfirmedRemoved -> {
                        consecutiveFailures = 0
                        confirmed.add(candidate)
                    }
                    VerificationOutcome.StillExists -> consecutiveFailures = 0
                    VerificationOutcome.TransientFailure -> {
                        hasFailures = true
                        consecutiveFailures++
                    }
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_VERIFY_FAILURES) {
                    throw IOException(
                        "Remote removal verification aborted after $consecutiveFailures consecutive failures"
                    )
                }
            }
        }
        return RemovalResolution(confirmed, hasVerificationFailures = hasFailures)
    }

    private suspend fun confirmRemoteRemoval(
        candidate: SyncTempIdEntity,
        auth: Triple<String, String, String>
    ): VerificationOutcome {
        val (user, token, salt) = auth
        val requestType = when (candidate.type) {
            "song", "album", "artist" -> candidate.type
            else -> error("Unknown library entity: ${candidate.type}")
        }
        return try {
            val response = when (requestType) {
                "song" -> api.getSong(candidate.id, user, token, salt).response
                "album" -> api.getAlbum(candidate.id, user, token, salt).response
                else -> api.getArtist(candidate.id, user, token, salt).response
            }
            if (response.status == "failed" && response.error?.code == RESOURCE_NOT_FOUND) {
                VerificationOutcome.ConfirmedRemoved
            } else {
                response.ensureOk()
                VerificationOutcome.StillExists
            }
        } catch (e: Exception) {
            if (e is CancellationException || isAuthError(e) || e is ServerUnavailableException) throw e
            LogManager.w(
                "SyncManager: Verification failed for ${candidate.type}/${candidate.id}, preserving item: ${e.message}"
            )
            VerificationOutcome.TransientFailure
        }
    }

    private fun isAuthError(e: Exception): Boolean {
        return when (e) {
            is HttpException -> e.code() in AUTH_HTTP_CODES
            is SubsonicApiException -> e.code in AUTH_SUBSONIC_ERROR_CODES
            else -> false
        }
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
        ignoreDropRatioGuard: Boolean
    ) {
        if (ignoreDropRatioGuard) return
        checkDimensionThreshold(local.songs, remote.songs, SafetyGuardException.Entity.SONGS)
        checkDimensionThreshold(local.albums, remote.albums, SafetyGuardException.Entity.ALBUMS)
        checkDimensionThreshold(local.artists, remote.artists, SafetyGuardException.Entity.ARTISTS)
    }

    private fun checkDimensionThreshold(
        localCount: Int,
        remoteCount: Int,
        entity: SafetyGuardException.Entity
    ) {
        if (localCount < MIN_ENTITIES_FOR_SAFETY) return
        val dropThreshold = localCount * SAFETY_GUARD_DROP_RATIO
        if (remoteCount < dropThreshold) {
            throw SafetyGuardException(entity, remoteCount, localCount)
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
            albumListHelper.syncAllAlbums(type = "newest")
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
        private const val MAX_REMOTE_VERIFY_CANDIDATES = 250
        private const val MAX_CONSECUTIVE_VERIFY_FAILURES = 16
        private const val RESOURCE_NOT_FOUND = 70
        private val AUTH_HTTP_CODES = setOf(401)
        private val AUTH_SUBSONIC_ERROR_CODES = setOf(40, 41, 50)
    }
}
