package de.lwp2070809.speculonic.data

import de.lwp2070809.speculonic.R


import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.domain.repository.AuthManager
import de.lwp2070809.speculonic.domain.repository.LyricsRepository
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.domain.repository.UrlBuilder
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@OptIn(UnstableApi::class)
@HiltWorker
class CacheSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: SubsonicRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val isDeepSync = inputData.getBoolean(KEY_DEEP_SYNC, false)
        val isForceScan = inputData.getBoolean(KEY_FORCE_SCAN, false)
        val shouldHealCovers = inputData.getBoolean(KEY_HEAL_COVERS, false)

        
        if (!isDeepSync && !isForceScan) {
            val lastScan = preferencesManager.lastCacheScanTime.first()
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScan < TimeUnit.HOURS.toMillis(CACHE_SCAN_COOLDOWN_HOURS)) {
                LogManager.i("CacheSync: Recently scanned within $CACHE_SCAN_COOLDOWN_HOURS hours. Skipping standard scan.")
                return Result.success()
            }
        }
        
        LogManager.i("CacheSync: Starting binary sync (Deep: $isDeepSync, Force: $isForceScan, HealCovers: $shouldHealCovers)...")
        
        val context = applicationContext
        val database = AppDatabase.getDatabase(context)
        val musicDao = database.musicDao()
        val downloadManager = DownloadManagerHelper.getDownloadManagerSuspend(context)
        
        val cacheLocation = preferencesManager.cacheLocation.first()
        val isSafEnabled = cacheLocation.isNotBlank()
        
        
        if (isSafEnabled) {
            val hasPermission = context.contentResolver.persistedUriPermissions.any { 
                it.uri.toString() == cacheLocation && it.isWritePermission && it.isReadPermission 
            }
            if (!hasPermission) {
                LogManager.e("CacheSync: SAF permission lost for $cacheLocation. Sync aborted to prevent accidental redownloads.")
                
                return Result.failure()
            }
        }

        val downloadCache = CacheManager.getDownloadCache(context)

        val serverUrl = preferencesManager.serverUrl.first()
        if (serverUrl.isBlank()) return Result.failure()
        
        val downloadController = DownloadController(context, repository)

        
        if (isSafEnabled) {
            migratePrivateCacheToSaf(context, musicDao, downloadCache)
        }

        var safFiles: Array<DocumentFile>? = null

        
        val cachedSongs = musicDao.getAllCachedSongs()
        if (cachedSongs.isNotEmpty()) {
            val existingSafSongIds = mutableSetOf<String>()
            if (isSafEnabled) {
                try {
                    val rootDoc = DocumentFile.fromTreeUri(context, cacheLocation.toUri())
                    val idPattern = Pattern.compile(".*\\[(.+)\\]\\.(mp3|flac|m4a|wav|aac|ogg|alac|aiff|dsf|lrc)$", Pattern.CASE_INSENSITIVE)
                    safFiles = rootDoc?.listFiles()
                    safFiles?.forEach { file ->
                        val fileName = file.name ?: ""
                        val matcher = idPattern.matcher(fileName)
                        if (matcher.matches()) {
                            val songId = matcher.group(1) ?: ""
                            if (de.lwp2070809.speculonic.util.FormatUtils.isSupportedAudioFile(fileName)) {
                                existingSafSongIds.add(songId)
                            }
                        }
                    }
                    LogManager.i("CacheSync: Batch pre-loaded ${existingSafSongIds.size} SAF files. Disabling O(N) IPC queries.")
                } catch (e: Exception) {
                    LogManager.e("CacheSync: Failed to pre-scan SAF directory for fast contains check", e)
                }
            }

            val groups = cachedSongs.groupBy { it.albumId ?: it.parent ?: "unknown" }
            val totalGroups = groups.size
            var processedGroups = 0
            val validator = CacheValidator(context)

            for ((groupId, groupSongs) in groups) {
                processedGroups++
                val progressValue = (processedGroups * 90 / totalGroups)
                val statusRes = if (isDeepSync) de.lwp2070809.speculonic.R.string.verifying_binary_consistency else de.lwp2070809.speculonic.R.string.scanning_local_files
                setProgress(workDataOf(PROGRESS to progressValue, STATUS to context.getString(statusRes, groupId)))
                
                val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()
                val isMetered = isMeteredNetwork(context)

                coroutineScope {
                    val semaphore = Semaphore(10)
                    groupSongs.map { song ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val physicalExists = if (isSafEnabled && song.localUri != null) {
                                    existingSafSongIds.contains(song.id)
                                } else {
                                    downloadCache.keys.contains(song.id)
                                }

                                var needsRedownload = !physicalExists
                                
                                if (physicalExists && isDeepSync) {
                                    
                                    if (!validator.checkBinaryConsistency(song.localUri?.toUri() ?: Uri.EMPTY, song, deepCheck = true)) {
                                        LogManager.i("CacheSync: Binary inconsistency detected for ${song.title}. Flagging for redownload.")
                                        needsRedownload = true
                                    }
                                }

                                if (needsRedownload) {
                                    
                                    if (!isDeepSync) {
                                        LogManager.w("CacheSync: Physical file missing for ${song.title}. Unlinking.")
                                        musicDao.updateSongCacheStatus(song.id, null, false)
                                        
                                        
                                        try {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                downloadManager.removeDownload(song.id)
                                            }
                                            LogManager.i("CacheSync: Successfully removed missing download task for ${song.title} from DownloadManager.")
                                        } catch (e: Exception) {
                                            LogManager.e("CacheSync: Failed to remove download task for ${song.title}", e)
                                        }
                                        return@withPermit
                                    }

                                    if (isMetered && !mobilePlayAllowed) {
                                        LogManager.w("CacheSync: Redownload skipped for ${song.title} due to metered network.")
                                        return@withPermit
                                    }

                                    LogManager.w("CacheSync: Cache invalid for ${song.title}. Triggering bit-perfect redownload.")
                                    musicDao.updateSongCacheStatus(song.id, null, false)
                                    
                                    try {
                                        downloadCache.removeResource(song.id)
                                        CacheManager.getPlaybackCache(context).removeResource(song.id)
                                    } catch (e: Exception) {
                                        LogManager.e("CacheSync: Failed to clean old cache before redownload", e)
                                    }
                                    
                                    val songModel = de.lwp2070809.speculonic.network.model.Song(
                                        id = song.id,
                                        title = song.title,
                                        album = song.album,
                                        artist = song.artist,
                                        albumId = song.albumId,
                                        duration = song.duration,
                                        coverArt = song.coverArt,
                                        path = song.path,
                                        size = song.size,
                                        md5 = song.md5
                                    )
                                    downloadController.downloadSong(songModel, isSilent = true)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        
        if (isSafEnabled) {
            setProgress(workDataOf(PROGRESS to 95, STATUS to context.getString(de.lwp2070809.speculonic.R.string.sync_scanning_orphans)))
            try {
                if (safFiles == null) {
                    val rootDoc = DocumentFile.fromTreeUri(context, cacheLocation.toUri())
                    safFiles = rootDoc?.listFiles()
                }
                val idPattern = Pattern.compile(".*\\[(.+)\\]\\.(mp3|flac|m4a|wav|aac|ogg|alac|aiff|dsf|lrc)$", Pattern.CASE_INSENSITIVE)
                
                safFiles?.forEach { file ->
                    val fileName = file.name ?: ""
                    val matcher = idPattern.matcher(fileName)
                    
                    if (matcher.matches()) {
                        val songId = matcher.group(1) ?: ""
                        val songInDb = musicDao.getSongById(songId)
                        
                        if (songInDb == null) {
                            LogManager.i("CacheSync: Orphaned file detected: $fileName. Ignoring to prevent accidental deletion in non-exclusive directories.")
                        } else if (de.lwp2070809.speculonic.util.FormatUtils.isSupportedAudioFile(fileName)) {
                            if (!songInDb.isFullyCached || songInDb.localUri == null) {
                                LogManager.i("CacheSync: Linked file by ID for ${songInDb.title} ($songId)")
                                musicDao.updateSongCacheStatus(songId, file.uri.toString(), true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.e("CacheSync: Failed to scan SAF directory", e)
            }
        }

        
        if (shouldHealCovers) {
            try {
                setProgress(workDataOf(PROGRESS to 98, STATUS to "正在补全专辑封面..."))
                LogManager.i("CacheSync: Executing cover art healing sync...")
                repository.syncAllCoverArt(onProgress = { progressStatus ->
                    setProgress(workDataOf(PROGRESS to 98, STATUS to progressStatus))
                })
            } catch (e: Exception) {
                LogManager.e("CacheSync: Failed to sync cover art in background healing task", e)
            }
        } else {
            LogManager.d("CacheSync: Cover art healing skipped for standard scan.")
        }

        
        preferencesManager.saveLastCacheScanTime(System.currentTimeMillis())

        setProgress(workDataOf(PROGRESS to 100, STATUS to context.getString(de.lwp2070809.speculonic.R.string.sync_completed)))
        LogManager.i("CacheSync: Sync completed.")
        return Result.success()
    }

    private suspend fun migratePrivateCacheToSaf(
        context: Context,
        musicDao: MusicDao,
        downloadCache: Cache
    ) {
        val cachedSongs = musicDao.getAllCachedSongs()
        val toMigrate = cachedSongs.filter {
            it.localUri.isNullOrBlank() && downloadCache.keys.contains(it.id)
        }

        if (toMigrate.isEmpty()) return

        LogManager.i("CacheSync: Found ${toMigrate.size} private cached songs waiting for migration to SAF.")
        val total = toMigrate.size

        val httpDataSourceFactory = OkHttpDataSource.Factory(NetworkModule.provideOkHttpClient())
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)

        val autoClean = preferencesManager.autoCleanCacheAfterExport.first()

        toMigrate.forEachIndexed { index, song ->
            val progressPercent = (index * 100 / total)
            val progressValue = 5 + (progressPercent * 40 / 100)
            setProgress(workDataOf(
                PROGRESS to progressValue,
                STATUS to "正在导出历史缓存 (${index + 1}/$total): ${song.title}"
            ))

            var lyrics: String? = null
            var coverArtBytes: ByteArray? = null
            try {
                val baseUrl = preferencesManager.serverUrl.first()
                val user = preferencesManager.username.first()
                val pass = preferencesManager.password.first()

                if (baseUrl.isNotBlank() && user.isNotBlank()) {
                    val api = NetworkModule.provideSubsonicService(baseUrl)
                    val authManager = AuthManager(user, pass.toCharArray())

                    val lyricsRepo = LyricsRepository(context, api, musicDao, authManager)
                    val (rawLyrics, _) = lyricsRepo.getLyricsData(song.id, song.artist, song.title, true)
                    lyrics = rawLyrics

                    if (!song.coverArt.isNullOrBlank()) {
                        val urlBuilder = UrlBuilder(baseUrl, authManager)
                        val coverUrl = urlBuilder.buildCoverArtUrl(song.coverArt)
                        val imageLoader = SingletonImageLoader.get(context)
                        val request = ImageRequest.Builder(context).data(coverUrl).build()
                        val result = imageLoader.execute(request)
                        if (result is SuccessResult) {
                            val bitmap = (result.image as? BitmapImage)?.bitmap
                            if (bitmap != null) {
                                val bos = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                                coverArtBytes = bos.toByteArray()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.w("CacheSync: Failed to fetch metadata for ${song.title} during migration, using audio only", e)
            }

            val songModel = Song(
                id = song.id,
                title = song.title,
                album = song.album,
                artist = song.artist,
                albumId = song.albumId,
                duration = song.duration,
                coverArt = song.coverArt,
                path = song.path,
                size = song.size,
                md5 = song.md5
            )

            val localUriResult = try {
                CacheExporter.exportToSaf(context, songModel, lyrics, coverArtBytes, cacheDataSourceFactory)
            } catch (e: SecurityException) {
                LogManager.e("CacheSync: SAF permission expired during migration", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(de.lwp2070809.speculonic.R.string.saf_permission_expired), Toast.LENGTH_LONG).show()
                }
                kotlin.Result.failure(e)
            }

            localUriResult.onSuccess { localUri ->
                musicDao.updateSongLocalUri(song.id, localUri)
                LogManager.i("CacheSync: Migrated ${song.title} to SAF successfully.")

                if (autoClean) {
                    LogManager.d("CacheSync: autoClean is enabled. Cleaning private cache for ${song.title}.")
                    downloadCache.removeResource(song.id)
                    try {
                        CacheManager.getPlaybackCache(context).removeResource(song.id)
                    } catch (e: Exception) {
                        LogManager.e("CacheSync: Failed to clean playback cache for migrated ${song.title}", e)
                    }
                }
            }.onFailure {
                LogManager.e("CacheSync: Migration failed for ${song.title}: ${it.message}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkUriExists(context: Context, uri: Uri): Boolean {
        return try {
            DocumentFile.fromSingleUri(context, uri)?.exists() == true
        } catch (e: Exception) {
            false
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

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val data = Data.Builder()
                .putBoolean(KEY_HEAL_COVERS, true)
                .build()
            val request = PeriodicWorkRequestBuilder<CacheSyncWorker>(CACHE_SCAN_COOLDOWN_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("CacheSync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        
        fun runOnce(context: Context, forceScan: Boolean = false, healCovers: Boolean = false) {
            val data = Data.Builder()
                .putBoolean(KEY_FORCE_SCAN, forceScan)
                .putBoolean(KEY_HEAL_COVERS, healCovers)
                .build()
            val request = OneTimeWorkRequestBuilder<CacheSyncWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("CacheSync_Once", ExistingWorkPolicy.REPLACE, request)
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
            WorkManager.getInstance(context).enqueueUniqueWork("CacheSync_Deep", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
