package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.MediaFormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class MigrationOutcome {
    var firstFailure: Throwable? = null
        private set

    fun recordFailure(error: Throwable) {
        if (error is CancellationException) throw error
        if (error is MissingPrivateAudioException) return
        if (firstFailure == null || error is SecurityException) firstFailure = error
    }
}

@OptIn(UnstableApi::class)
private data class VerifySongsConfig(
    val context: Context,
    val musicDao: MusicDao,
    val isSafEnabled: Boolean,
    val existingSafSongIds: Set<String>,
    val downloadCache: Cache,
    val downloadManager: DownloadManager
)

@OptIn(UnstableApi::class)
@HiltWorker
class CacheSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase,
    private val cacheManager: CacheManager,
    private val downloadManagerHelper: DownloadManagerHelper,
    private val cacheExporter: CacheExporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isForceScan = inputData.getBoolean(KEY_FORCE_SCAN, false)
        if (shouldSkipDueToCooldown(isForceScan)) return Result.success()

        LogManager.i("CacheSync: Starting binary sync (Force: $isForceScan)...")
        val context = applicationContext
        val cacheLocation = preferencesManager.cacheLocation.first()
        val isSafEnabled = cacheLocation.isNotBlank()
        if (isSafEnabled && !hasValidSafPermission(context, cacheLocation)) {
            return failureResult(context.getString(R.string.saf_permission_expired), needsSaf = true)
        }
        if (preferencesManager.serverUrl.first().isBlank()) return Result.failure()

        val musicDao = database.musicDao()
        val migrationOutcome = if (isSafEnabled) migratePrivateCacheToSaf(context, musicDao) else MigrationOutcome()
        if (migrationOutcome.firstFailure is SecurityException) {
            return failureResult(context.getString(R.string.saf_permission_expired), needsSaf = true)
        }
        cacheExporter.cleanupOrphanedPrivateFiles(musicDao)

        val (safEntries, existingSafIds) = preloadSafIfEnabled(context, cacheLocation).getOrElse { error ->
            return failureResult(error.localizedMessage ?: error.javaClass.simpleName, needsSaf = true)
        }

        verifyAndHeal(context, musicDao, cacheLocation, safEntries, existingSafIds)
        migrationOutcome.firstFailure?.let { error ->
            val detail = error.localizedMessage ?: error.javaClass.simpleName
            return failureResult(context.getString(R.string.export_failed, detail))
        }

        preferencesManager.saveLastCacheScanTime(System.currentTimeMillis())
        setProgress(workDataOf(PROGRESS to COMPLETED_PROGRESS, STATUS to context.getString(R.string.sync_completed)))
        LogManager.i("CacheSync: Sync completed.")
        return Result.success()
    }

    private fun failureResult(status: String?, needsSaf: Boolean = false): Result =
        Result.failure(workDataOf(STATUS to status, KEY_ERROR_NEEDS_SAF to needsSaf))

    private fun preloadSafIfEnabled(
        context: Context,
        cacheLocation: String
    ): kotlin.Result<Pair<List<SafChildEntry>?, Set<String>>> {
        if (cacheLocation.isBlank()) return kotlin.Result.success(null to emptySet())
        return runCatching { preloadSafAudioFiles(context, cacheLocation) }.onFailure {
            if (it is CancellationException) throw it
            LogManager.e("CacheSync: Unable to read SAF directory", it)
        }
    }

    private suspend fun verifyAndHeal(
        context: Context,
        musicDao: MusicDao,
        cacheLocation: String,
        safEntries: List<SafChildEntry>?,
        existingSafIds: Set<String>
    ) {
        val isSafEnabled = cacheLocation.isNotBlank()
        val downloadManager = downloadManagerHelper.getDownloadManagerSuspend()
        val downloadCache = cacheManager.acquireDownloadCache()
        val cachedSongs = musicDao.getAllCachedSongs()
        if (cachedSongs.isNotEmpty()) {
            verifyCachedSongs(
                cachedSongs = cachedSongs,
                config = VerifySongsConfig(
                    context = context,
                    musicDao = musicDao,
                    isSafEnabled = isSafEnabled,
                    existingSafSongIds = existingSafIds,
                    downloadCache = downloadCache,
                    downloadManager = downloadManager
                )
            )
        }
        val updatedSafEntries = if (isSafEnabled) {
            scanOrphanedSafEntries(context, musicDao, cacheLocation, safEntries)
        } else {
            safEntries
        }
        if (updatedSafEntries != null) {
            LogManager.d("CacheSync: SAF entries processed (${updatedSafEntries.size}).")
        }
    }

    private suspend fun shouldSkipDueToCooldown(isForceScan: Boolean): Boolean {
        if (isForceScan) return false
        val lastScan = preferencesManager.lastCacheScanTime.first()
        val currentTime = System.currentTimeMillis()
        val cooldownMs = TimeUnit.HOURS.toMillis(CACHE_SCAN_COOLDOWN_HOURS)
        if (currentTime - lastScan < cooldownMs) {
            LogManager.i(
                "CacheSync: Recently scanned within $CACHE_SCAN_COOLDOWN_HOURS hours. " +
                    "Skipping standard scan."
            )
            return true
        }
        return false
    }

    private fun hasValidSafPermission(context: Context, cacheLocation: String): Boolean {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == cacheLocation && it.isWritePermission && it.isReadPermission
        }
        if (!hasPermission) {
            LogManager.e(
                "CacheSync: SAF permission lost for $cacheLocation. " +
                    "Sync aborted to prevent accidental redownloads."
            )
        }
        return hasPermission
    }

    private fun preloadSafAudioFiles(context: Context, cacheLocation: String): Pair<List<SafChildEntry>, Set<String>> {
        val entries = SafDirectoryHelper.listChildEntries(context, cacheLocation.toUri())
        val ids = entries.asSequence().filter { MediaFormatUtils.isSupportedAudioFile(it.name) }
            .mapNotNull { MediaFormatUtils.extractSongIdFromFileName(it.name) }.toSet()
        return entries to ids
    }

    private suspend fun verifyCachedSongs(
        cachedSongs: List<SongEntity>,
        config: VerifySongsConfig
    ) {
        val totalSongs = cachedSongs.size
        val processedSongsCount = AtomicInteger(0)
        val globalSemaphore = Semaphore(MAX_VERIFY_CONCURRENCY)
        val progressMutex = Mutex()
        var lastReportedProgress = -1

        coroutineScope {
            cachedSongs.map { song ->
                async(Dispatchers.IO) {
                    globalSemaphore.withPermit {
                        verifySingleSong(song, config)
                        val processed = processedSongsCount.incrementAndGet()
                        val progressValue = processed * VERIFY_PROGRESS_WEIGHT / totalSongs
                        progressMutex.withLock {
                            if (progressValue > lastReportedProgress) {
                                lastReportedProgress = progressValue
                                setProgress(
                                    workDataOf(
                                        PROGRESS to progressValue,
                                        STATUS to config.context.getString(R.string.scanning_local_files, song.title)
                                    )
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun verifySingleSong(
        song: SongEntity,
        config: VerifySongsConfig
    ) {
        val physicalExists = checkPhysicalFileExists(
            song = song,
            isSafEnabled = config.isSafEnabled,
            existingSafSongIds = config.existingSafSongIds,
            downloadCache = config.downloadCache
        )
        if (!physicalExists) {
            unlinkMissingSong(
                musicDao = config.musicDao,
                song = song,
                downloadManager = config.downloadManager
            )
        }
    }

    private fun checkPhysicalFileExists(
        song: SongEntity,
        isSafEnabled: Boolean,
        existingSafSongIds: Set<String>,
        downloadCache: Cache
    ): Boolean {
        val localUri = song.localUri
        return when {
            isSafEnabled && localUri != null -> {
                if (localUri.startsWith("file:")) {
                    val path = localUri.toUri().path
                    path != null && File(path).exists()
                } else {
                    existingSafSongIds.contains(song.id)
                }
            }
            !isSafEnabled && localUri != null && localUri.startsWith("file:") -> {
                val path = localUri.toUri().path
                path != null && File(path).exists()
            }
            else -> downloadCache.keys.contains(song.id)
        }
    }

    private suspend fun unlinkMissingSong(
        musicDao: MusicDao,
        song: SongEntity,
        downloadManager: DownloadManager
    ) {
        LogManager.w("CacheSync: Physical file missing for ${song.title}. Unlinking.")
        musicDao.updateSongCacheStatus(song.id, null, false)
        try {
            downloadManager.removeDownload(song.id)
            LogManager.i(
                "CacheSync: Successfully removed missing download task for " +
                    "${song.title} from DownloadManager."
            )
        } catch (e: Exception) {
            LogManager.e("CacheSync: Failed to remove download task for ${song.title}", e)
        }
    }

    private suspend fun scanOrphanedSafEntries(
        context: Context,
        musicDao: MusicDao,
        cacheLocation: String,
        initialSafEntries: List<SafChildEntry>?
    ): List<SafChildEntry>? {
        setProgress(
            workDataOf(
                PROGRESS to ORPHAN_SCAN_PROGRESS,
                STATUS to context.getString(R.string.sync_scanning_orphans)
            )
        )
        var resolvedEntries = initialSafEntries
        try {
            val entries = initialSafEntries ?: SafDirectoryHelper.listChildEntries(
                context,
                cacheLocation.toUri()
            ).also { resolvedEntries = it }
            val audioEntries = entries.mapNotNull { entry ->
                val songId = MediaFormatUtils.extractSongIdFromFileName(entry.name)
                if (songId != null) songId to entry else null
            }
            val songsById = if (audioEntries.isNotEmpty()) {
                musicDao.getSongsByIds(audioEntries.map { it.first }.distinct())
                    .associateBy { it.id }
            } else {
                emptyMap()
            }

            audioEntries.forEach { (songId, entry) ->
                linkOrLogOrphanSafEntry(musicDao, songId, entry, songsById[songId])
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("CacheSync: Failed to scan SAF directory", e)
        }
        return resolvedEntries
    }

    private suspend fun linkOrLogOrphanSafEntry(
        musicDao: MusicDao,
        songId: String,
        entry: SafChildEntry,
        songInDb: SongEntity?
    ) {
        val fileName = entry.name
        if (songInDb == null) {
            LogManager.i(
                "CacheSync: Orphaned file detected: $fileName. " +
                    "Ignoring to prevent accidental deletion in non-exclusive directories."
            )
            return
        }
        if (!MediaFormatUtils.isSupportedAudioFile(fileName)) return
        if (songInDb.isFullyCached && songInDb.localUri != null) return

        val fileExt = fileName.substringAfterLast('.', "").lowercase()
        val isTranscoded = fileExt != (songInDb.suffix ?: "").lowercase()
        LogManager.i(
            "CacheSync: Linked file by ID for ${songInDb.title} ($songId, isTranscoded: $isTranscoded)"
        )
        musicDao.updateSongCacheStatus(songId, entry.uri.toString(), true, isTranscoded)
    }

    private suspend fun migratePrivateCacheToSaf(
        context: Context,
        musicDao: MusicDao
    ): MigrationOutcome {
        val cachedSongs = musicDao.getAllCachedSongs()
        val toMigrate = cachedSongs.filter {
            !it.localUri.isNullOrBlank() && it.localUri.startsWith("file:")
        }
        val outcome = MigrationOutcome()
        if (toMigrate.isEmpty()) return outcome

        LogManager.i("CacheSync: Found ${toMigrate.size} songs waiting for migration to SAF.")
        val total = toMigrate.size
        for ((index, song) in toMigrate.withIndex()) {
            val progressPercent = index * PERCENT_SCALE / total
            val progressValue = MIGRATION_BASE_PROGRESS +
                (progressPercent * MIGRATION_PROGRESS_RANGE / PERCENT_SCALE)
            setProgress(
                workDataOf(
                    PROGRESS to progressValue,
                    STATUS to context.getString(
                        R.string.exporting_historical_cache,
                        index + 1,
                        total,
                        song.title
                    )
                )
            )
            val error = migrateSingleSongToSaf(musicDao, song) ?: continue
            outcome.recordFailure(error)
            if (error is SecurityException) return outcome
        }
        return outcome
    }

    private suspend fun migrateSingleSongToSaf(
        musicDao: MusicDao,
        song: SongEntity
    ): Throwable? {
        val existingUri = song.localUri ?: return IllegalStateException("Not a private file")
        val localUriResult = try {
            cacheExporter.exportPrivateFileToSaf(EntityMapper.toSong(song), existingUri)
        } catch (e: SecurityException) {
            LogManager.e("CacheSync: SAF permission expired during migration", e)
            return e
        }

        localUriResult.onSuccess { localUri ->
            musicDao.updateSongLocalUri(song.id, localUri)
            LogManager.i("CacheSync: Migrated ${song.title} to SAF successfully.")
            cleanupMigratedPrivateFiles(song, existingUri)
        }
        val error = localUriResult.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error != null) LogManager.e("CacheSync: Migration failed for ${song.title}", error)
        return error
    }

    private suspend fun cleanupMigratedPrivateFiles(song: SongEntity, oldLocalUri: String?) {
        deleteMigratedPrivateAudioAndLyric(oldLocalUri)

        LogManager.d("CacheSync: Cleaning private cache for ${song.title}.")
        try {
            cacheManager.acquireDownloadCache().removeResource(song.id)
            PlaybackBuffer.remove(cacheManager.acquirePlaybackCache(), song.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e(
                "CacheSync: Failed to clean playback/download cache for migrated ${song.title}",
                e
            )
        }
    }

    private fun deleteMigratedPrivateAudioAndLyric(oldLocalUri: String?) {
        if (oldLocalUri == null || !oldLocalUri.startsWith("file:")) return
        try {
            val path = oldLocalUri.toUri().path ?: return
            val file = File(path)
            if (file.exists()) {
                file.delete()
                LogManager.d("CacheSync: Deleted migrated private audio file: $path")
            }
            val lrcFile = File(FormatUtils.replaceExtensionWithLrc(path))
            if (lrcFile.exists()) {
                lrcFile.delete()
                LogManager.d("CacheSync: Deleted migrated private lyric file: ${lrcFile.path}")
            }
        } catch (e: Exception) {
            LogManager.e("CacheSync: Failed to clean up private files after migration", e)
        }
    }

    companion object {
        const val MANUAL_SCAN_WORK_NAME = "CacheSync_Once"
        const val KEY_FORCE_SCAN = "force_scan"
        const val PROGRESS = "progress"
        const val STATUS = "status"
        const val KEY_ERROR_NEEDS_SAF = "error_needs_saf"

        const val CACHE_SCAN_COOLDOWN_HOURS = 168L

        private const val MAX_VERIFY_CONCURRENCY = 5
        private const val VERIFY_PROGRESS_WEIGHT = 90
        private const val MIGRATION_BASE_PROGRESS = 5
        private const val MIGRATION_PROGRESS_RANGE = 40
        private const val PERCENT_SCALE = 100
        private const val ORPHAN_SCAN_PROGRESS = 95
        private const val COMPLETED_PROGRESS = 100

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request = PeriodicWorkRequestBuilder<CacheSyncWorker>(
                CACHE_SCAN_COOLDOWN_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("CacheSync", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runOnce(
            context: Context,
            forceScan: Boolean = false,
            replaceExisting: Boolean = false
        ): UUID {
            val data = Data.Builder()
                .putBoolean(KEY_FORCE_SCAN, forceScan)
                .build()
            val request = OneTimeWorkRequestBuilder<CacheSyncWorker>()
                .setInputData(data)
                .build()
            val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_SCAN_WORK_NAME, policy, request)
            return request.id
        }
    }
}
