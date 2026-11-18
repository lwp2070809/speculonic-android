package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.DownloadController
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

@OptIn(UnstableApi::class)
@HiltWorker
class CacheSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase,
    private val cacheManager: CacheManager,
    private val downloadManagerHelper: DownloadManagerHelper,
    private val downloadController: DownloadController,
    private val cacheExporter: CacheExporter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isDeepSync = inputData.getBoolean(KEY_DEEP_SYNC, false)
        val isForceScan = inputData.getBoolean(KEY_FORCE_SCAN, false)
        val shouldHealCovers = inputData.getBoolean(KEY_HEAL_COVERS, false)

        if (shouldSkipDueToCooldown(isDeepSync, isForceScan)) {
            return Result.success()
        }

        LogManager.i(
            "CacheSync: Starting binary sync " +
                "(Deep: $isDeepSync, Force: $isForceScan, HealCovers: $shouldHealCovers)..."
        )

        val context = applicationContext
        val musicDao = database.musicDao()
        val downloadManager = downloadManagerHelper.getDownloadManagerSuspend()
        val cacheLocation = preferencesManager.cacheLocation.first()
        val isSafEnabled = cacheLocation.isNotBlank()

        if (isSafEnabled && !hasValidSafPermission(context, cacheLocation)) {
            return Result.failure()
        }

        val downloadCache = cacheManager.acquireDownloadCache()
        val serverUrl = preferencesManager.serverUrl.first()
        if (serverUrl.isBlank()) return Result.failure()

        if (isSafEnabled) {
            migratePrivateCacheToSaf(context, musicDao)
        }
        cleanupOrphanedPrivateFiles(context, musicDao)

        val (safEntries, existingSafIds) = if (isSafEnabled) {
            preloadSafAudioFiles(context, cacheLocation)
        } else {
            null to emptySet()
        }

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
                    validator = CacheValidator(context),
                    isDeepSync = isDeepSync,
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

        healCoversIfRequested(context, shouldHealCovers)

        preferencesManager.saveLastCacheScanTime(System.currentTimeMillis())
        setProgress(
            workDataOf(
                PROGRESS to COMPLETED_PROGRESS,
                STATUS to context.getString(R.string.sync_completed)
            )
        )
        LogManager.i("CacheSync: Sync completed.")
        return Result.success()
    }

    private suspend fun shouldSkipDueToCooldown(
        isDeepSync: Boolean,
        isForceScan: Boolean
    ): Boolean {
        if (isDeepSync || isForceScan) return false
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

    private fun preloadSafAudioFiles(
        context: Context,
        cacheLocation: String
    ): Pair<List<SafChildEntry>?, Set<String>> {
        val existingSafSongIds = mutableSetOf<String>()
        return try {
            val entries = SafDirectoryHelper.listChildEntries(context, cacheLocation.toUri())
            entries.forEach { entry ->
                val fileName = entry.name
                val songId = MediaFormatUtils.extractSongIdFromFileName(fileName)
                if (songId != null && MediaFormatUtils.isSupportedAudioFile(fileName)) {
                    existingSafSongIds.add(songId)
                }
            }
            LogManager.i(
                "CacheSync: Batch pre-loaded ${existingSafSongIds.size} SAF files. " +
                    "Disabling O(N) IPC queries."
            )
            entries to existingSafSongIds
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("CacheSync: Failed to pre-scan SAF directory for fast contains check", e)
            null to existingSafSongIds
        }
    }

    private data class VerifySongsConfig(
        val context: Context,
        val musicDao: MusicDao,
        val isSafEnabled: Boolean,
        val existingSafSongIds: Set<String>,
        val downloadCache: Cache,
        val validator: CacheValidator,
        val isDeepSync: Boolean,
        val downloadManager: DownloadManager
    )

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
                                val statusRes = if (config.isDeepSync) {
                                    R.string.verifying_binary_consistency
                                } else {
                                    R.string.scanning_local_files
                                }
                                setProgress(
                                    workDataOf(
                                        PROGRESS to progressValue,
                                        STATUS to config.context.getString(statusRes, song.title)
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
        var needsRedownload = !physicalExists
        if (physicalExists && config.isDeepSync && song.localUri != null) {
            val isConsistent = config.validator.checkBinaryConsistency(
                song.localUri.toUri(),
                song,
                deepCheck = true
            )
            if (!isConsistent) {
                LogManager.i(
                    "CacheSync: Binary inconsistency detected for ${song.title}. " +
                        "Flagging for redownload."
                )
                needsRedownload = true
            }
        }
        if (needsRedownload) {
            handleMissingOrInconsistentSong(
                context = config.context,
                musicDao = config.musicDao,
                song = song,
                isDeepSync = config.isDeepSync,
                downloadCache = config.downloadCache,
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

    private suspend fun handleMissingOrInconsistentSong(
        context: Context,
        musicDao: MusicDao,
        song: SongEntity,
        isDeepSync: Boolean,
        downloadCache: Cache,
        downloadManager: DownloadManager
    ) {
        if (!isDeepSync) {
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
            return
        }

        val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()
        if (isMeteredNetwork(context) && !mobilePlayAllowed) {
            LogManager.w("CacheSync: Redownload skipped for ${song.title} due to metered network.")
            return
        }

        LogManager.i(
            "CacheSync: Triggering redownload for inconsistent/missing song: ${song.title}"
        )
        musicDao.updateSongCacheStatus(song.id, null, false)
        try {
            downloadCache.removeResource(song.id)
            cacheManager.acquirePlaybackCache().removeResource(song.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("CacheSync: Failed to clean old cache before redownload", e)
        }
        downloadController.downloadSong(EntityMapper.toSong(song), isSilent = true)
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

    private suspend fun healCoversIfRequested(
        context: Context,
        shouldHealCovers: Boolean
    ) {
        if (!shouldHealCovers) {
            LogManager.d("CacheSync: Cover art healing skipped for standard scan.")
            return
        }
        try {
            setProgress(
                workDataOf(
                    PROGRESS to HEAL_COVER_PROGRESS,
                    STATUS to context.getString(R.string.healing_cover_art)
                )
            )
            LogManager.i("CacheSync: Executing cover art healing sync...")
            repository.syncAllCoverArt(
                onProgress = { progressStatus ->
                    setProgress(
                        workDataOf(
                            PROGRESS to HEAL_COVER_PROGRESS,
                            STATUS to progressStatus
                        )
                    )
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("CacheSync: Failed to sync cover art in background healing task", e)
        }
    }

    private suspend fun migratePrivateCacheToSaf(
        context: Context,
        musicDao: MusicDao
    ) {
        val cachedSongs = musicDao.getAllCachedSongs()
        val toMigrate = cachedSongs.filter {
            !it.localUri.isNullOrBlank() && it.localUri.startsWith("file:")
        }
        if (toMigrate.isEmpty()) return

        LogManager.i("CacheSync: Found ${toMigrate.size} songs waiting for migration to SAF.")
        val total = toMigrate.size

        toMigrate.forEachIndexed { index, song ->
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
            migrateSingleSongToSaf(context, musicDao, song)
        }
    }

    private suspend fun migrateSingleSongToSaf(
        context: Context,
        musicDao: MusicDao,
        song: SongEntity
    ) {
        val songModel = EntityMapper.toSong(song)
        val localUriResult = try {
            val existingUri = song.localUri
            if (existingUri != null && existingUri.startsWith("file:")) {
                cacheExporter.exportPrivateFileToSaf(songModel, existingUri)
            } else {
                kotlin.Result.failure(Exception("Not a private file"))
            }
        } catch (e: SecurityException) {
            LogManager.e("CacheSync: SAF permission expired during migration", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.saf_permission_expired),
                    Toast.LENGTH_LONG
                ).show()
            }
            kotlin.Result.failure(e)
        }

        localUriResult.onSuccess { localUri ->
            val oldLocalUri = song.localUri
            musicDao.updateSongLocalUri(song.id, localUri)
            LogManager.i("CacheSync: Migrated ${song.title} to SAF successfully.")
            cleanupMigratedPrivateFiles(song, oldLocalUri)
        }.onFailure {
            LogManager.e("CacheSync: Migration failed for ${song.title}: ${it.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_failed, it.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun cleanupMigratedPrivateFiles(song: SongEntity, oldLocalUri: String?) {
        deleteMigratedPrivateAudioAndLyric(oldLocalUri)

        LogManager.d("CacheSync: Cleaning private cache for ${song.title}.")
        try {
            cacheManager.acquireDownloadCache().removeResource(song.id)
            cacheManager.acquirePlaybackCache().removeResource(song.id)
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

    private suspend fun cleanupOrphanedPrivateFiles(context: Context, musicDao: MusicDao) {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val privateExportedDir = File(baseDir, "media_exported_private")
        if (!privateExportedDir.exists()) return

        val validPrivatePaths = musicDao.getAllCachedSongs()
            .mapNotNull { it.localUri }
            .filter { it.startsWith("file:") }
            .mapNotNull { it.toUri().path }
            .toSet()
        val validPrivateLrcPaths = validPrivatePaths
            .map { FormatUtils.replaceExtensionWithLrc(it) }
            .toSet()

        privateExportedDir.listFiles()
            ?.filter { it.isFile && it.name != ".nomedia" }
            ?.forEach { file ->
                val isLrc = file.name.endsWith(".lrc")
                val isAudio = FormatUtils.isSupportedAudioFile(file.name)
                val isOrphan = (isAudio && !validPrivatePaths.contains(file.absolutePath)) ||
                    (isLrc && !validPrivateLrcPaths.contains(file.absolutePath))
                if (isOrphan) {
                    file.delete()
                    LogManager.i("CacheSync: Deleted orphaned private file: ${file.name}")
                }
            }
    }

    private fun isMeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val KEY_DEEP_SYNC = "deep_sync"
        const val KEY_FORCE_SCAN = "force_scan"
        const val KEY_HEAL_COVERS = "heal_covers"
        const val PROGRESS = "progress"
        const val STATUS = "status"

        const val CACHE_SCAN_COOLDOWN_HOURS = 168L

        private const val MAX_VERIFY_CONCURRENCY = 5
        private const val VERIFY_PROGRESS_WEIGHT = 90
        private const val MIGRATION_BASE_PROGRESS = 5
        private const val MIGRATION_PROGRESS_RANGE = 40
        private const val PERCENT_SCALE = 100
        private const val ORPHAN_SCAN_PROGRESS = 95
        private const val HEAL_COVER_PROGRESS = 98
        private const val COMPLETED_PROGRESS = 100

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val data = Data.Builder()
                .putBoolean(KEY_HEAL_COVERS, true)
                .build()
            val request = PeriodicWorkRequestBuilder<CacheSyncWorker>(
                CACHE_SCAN_COOLDOWN_HOURS,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("CacheSync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnce(
            context: Context,
            forceScan: Boolean = false,
            healCovers: Boolean = false
        ): UUID {
            val data = Data.Builder()
                .putBoolean(KEY_FORCE_SCAN, forceScan)
                .putBoolean(KEY_HEAL_COVERS, healCovers)
                .build()
            val request = OneTimeWorkRequestBuilder<CacheSyncWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("CacheSync_Once", ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        fun runDeepSync(context: Context) {
            val data = Data.Builder()
                .putBoolean(KEY_DEEP_SYNC, true)
                .putBoolean(KEY_FORCE_SCAN, true)
                .putBoolean(KEY_HEAL_COVERS, true)
                .build()
            val request = OneTimeWorkRequestBuilder<CacheSyncWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("CacheSync_Deep", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
