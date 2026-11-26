package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import coil3.SingletonImageLoader
import de.lwp2070809.speculonic.domain.repository.CoverArtSyncManager
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.SafDirectoryHelper
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

@OptIn(UnstableApi::class)
class CacheOperations @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val cacheManager: CacheManager,
    private val downloadTracker: DownloadTracker
) {

    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun runCacheOp(
        errorLog: String,
        crossinline block: suspend () -> Unit
    ): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogManager.e(errorLog, e)
        Result.failure(e)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> runBestEffort(
        errorLog: String,
        defaultValue: T,
        warnOnly: Boolean = false,
        block: () -> T
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (warnOnly) {
            LogManager.w("$errorLog: ${e.message}")
        } else {
            LogManager.e(errorLog, e)
        }
        defaultValue
    }

    private suspend fun runCacheReset(operationName: String, clearDownloads: Boolean, reset: suspend () -> Unit): Result<Unit> =
        runCacheOp("CacheOperations: $operationName failed") { downloadTracker.resetWhileTrackingPaused(clearDownloads, reset) }

    private fun deleteCacheDirectory(directory: File, required: Boolean) {
        if (!directory.deleteRecursively() && required) {
            throw IOException("Could not clear ${directory.name} before changing accounts")
        }
    }

    private suspend fun resetManagedCache(
        operationName: String,
        clearPlaybackBuffer: Boolean = false,
        clearCoverArt: Boolean = false,
        clearPersistent: Boolean = false,
        resetDb: Boolean = false,
        preserveAccountData: Boolean = true,
        clearAccountTables: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCacheReset(operationName, clearDownloads = clearPersistent) {
            runBestEffort("CacheOperations: Failed to stop DownloadServices", Unit) {
                context.stopService(android.content.Intent(context, de.lwp2070809.speculonic.playback.DownloadService::class.java))
                context.stopService(
                    android.content.Intent(context, de.lwp2070809.speculonic.playback.SilentDownloadService::class.java)
                )
            }

            if (clearCoverArt) CoverArtSyncManager.clear(SingletonImageLoader.get(context))
            cacheManager.executeWithCacheReleaseLock(preservePlayback = preserveAccountData) {
                val requireCleanup = clearAccountTables
                if (clearPlaybackBuffer) {
                    deleteCacheDirectory(File(context.cacheDir, "media_playback_buffer"), requireCleanup)
                }
                if (clearPersistent) {
                    val baseExternalDir = context.getExternalFilesDir(null) ?: context.filesDir
                    deleteCacheDirectory(File(baseExternalDir, "media_persistent_cache"), requireCleanup)
                    deleteCacheDirectory(File(baseExternalDir, "media_exported_private"), requireCleanup)
                    val dbName = "exoplayer_internal.db"
                    if (requireCleanup) {
                        if (!context.deleteDatabase(dbName) && context.getDatabasePath(dbName).exists()) {
                            throw IOException("Could not clear the download history before changing accounts")
                        }
                    } else {
                        runBestEffort("CacheOperations: Failed to delete $dbName on $operationName", Unit) {
                            context.deleteDatabase(dbName)
                        }
                    }
                }
            }

            if (clearAccountTables) {
                database.clearAllTables()
            } else if (resetDb) {
                runBestEffort("CacheOperations: Failed to reset database cache status on $operationName", Unit) {
                    if (preserveAccountData) {
                        database.musicDao().resetNonSafCacheStatus()
                    } else {
                        database.musicDao().resetAllCacheStatus()
                    }
                }
            }

        }
    }

    suspend fun clearAllCache(preserveAccountData: Boolean = true): Result<Unit> = resetManagedCache(
        operationName = "clearAllCache",
        clearPlaybackBuffer = true,
        clearCoverArt = true,
        clearPersistent = true,
        resetDb = true,
        preserveAccountData = preserveAccountData
    )

    internal suspend fun clearLegacyAccountData(): Result<Unit> = resetManagedCache(
        operationName = "clearLegacyAccountData",
        clearPlaybackBuffer = true,
        clearCoverArt = true,
        clearPersistent = true,
        preserveAccountData = false,
        clearAccountTables = true
    )

    suspend fun clearPlaybackCache(): Result<Unit> = resetManagedCache(
        operationName = "clearPlaybackCache",
        clearPlaybackBuffer = true
    )

    suspend fun clearCoverArtCache(): Result<Unit> = withContext(Dispatchers.IO) {
        runCacheOp("CacheOperations: clearCoverArtCache failed") {
            CoverArtSyncManager.clear(SingletonImageLoader.get(context))
        }
    }

    suspend fun clearSongDownloads(): Result<Unit> = resetManagedCache(
        operationName = "clearSongDownloads",
        clearPersistent = true,
        resetDb = true
    )

    suspend fun calculateCacheSizes(cacheLocation: String): CacheBreakdown = withContext(Dispatchers.IO) {
        val isSafEnabled = cacheLocation.isNotBlank()
        // 播放缓冲仅计入 media_playback_buffer，与 clearPlaybackCache() 清理范围严格一致
        val playbackBytes = getDirectorySize(File(context.cacheDir, "media_playback_buffer"))
        val coverArtBytes = getDirectorySize(File(context.cacheDir, "image_cache"))

        val persistentCacheBytes = getDirectorySize(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache")
        )
        val privateExportedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
        val privateExportedBytes = getDirectorySize(privateExportedDir)
        val songBytes = if (isSafEnabled) 0L else persistentCacheBytes + privateExportedBytes

        val totalCache = getDirectorySize(context.cacheDir)
        val totalFiles = getDirectorySize(context.filesDir)
        val totalExternalFiles = getDirectorySize(context.getExternalFilesDir(null))
        val totalPrivate = totalCache + totalFiles + totalExternalFiles
        val otherBytes = (totalPrivate - playbackBytes - coverArtBytes - songBytes).coerceAtLeast(0L)

        val externalBytes = if (isSafEnabled) {
            runBestEffort("CacheOperations: Failed to calculate external cache size", 0L, warnOnly = true) {
                SafDirectoryHelper.listChildEntries(context, cacheLocation.toUri()).sumOf { entry ->
                    if (de.lwp2070809.speculonic.util.FormatUtils.isSupportedAudioFile(entry.name)) {
                        entry.size
                    } else {
                        0L
                    }
                }
            }
        } else {
            0L
        }

        CacheBreakdown(playbackBytes, coverArtBytes, songBytes, otherBytes, externalBytes)
    }

    private fun getDirectorySize(directory: File?): Long {
        if (directory == null || !directory.exists()) return 0L
        var size: Long = 0
        directory.listFiles()?.forEach { file -> 
            size += if (file.isDirectory) getDirectorySize(file) else file.length() 
        }
        return size
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }
}

data class CacheBreakdown(
    val playbackBytes: Long,
    val coverArtBytes: Long,
    val songBytes: Long,
    val otherBytes: Long,
    val externalBytes: Long
)
