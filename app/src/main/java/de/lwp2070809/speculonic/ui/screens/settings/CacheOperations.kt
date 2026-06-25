package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

@OptIn(UnstableApi::class)
class CacheOperations(private val context: Context) {
    
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        DownloadManagerHelper.release()

        CacheManager.executeWithCacheReleaseLock {
            File(context.cacheDir, "media_playback_buffer").deleteRecursively()
            File(context.cacheDir, "image_cache").deleteRecursively()
            val internalPersistentDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache")
            internalPersistentDir.deleteRecursively()
            val privateExportedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            privateExportedDir.deleteRecursively()
            try {
                context.deleteDatabase("exoplayer_internal.db")
            } catch (e: Exception) {
                LogManager.e("CacheOperations: Failed to delete exoplayer_internal.db on clearAllCache", e)
            }
        }

        try {
            val db = AppDatabase.getDatabase(context)
            db.musicDao().resetAllCacheStatus()
        } catch (e: Exception) {
            LogManager.e("CacheOperations: Failed to reset database cache status on clearAllCache", e)
        }
        DownloadTracker.clearAll()
        DownloadTracker.init(context)
    }

    suspend fun clearPlaybackCache() = withContext(Dispatchers.IO) {
        CacheManager.executeWithCacheReleaseLock {
            File(context.cacheDir, "media_playback_buffer").deleteRecursively()
        }
    }

    suspend fun clearCoverArtCache() = withContext(Dispatchers.IO) {
        CacheManager.executeWithCacheReleaseLock {
            File(context.cacheDir, "image_cache").deleteRecursively()
        }
    }

    suspend fun clearSongDownloads() = withContext(Dispatchers.IO) {
        DownloadManagerHelper.release()
        CacheManager.executeWithCacheReleaseLock {
            val internalPersistentDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache")
            internalPersistentDir.deleteRecursively()
            val privateExportedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            privateExportedDir.deleteRecursively()
            try {
                context.deleteDatabase("exoplayer_internal.db")
            } catch (e: Exception) {
                LogManager.e("CacheOperations: Failed to delete exoplayer_internal.db on clearSongDownloads", e)
            }
        }

        try {
            val db = AppDatabase.getDatabase(context)
            db.musicDao().resetAllCacheStatus()
        } catch (e: Exception) {
            LogManager.e("CacheOperations: Failed to reset database cache status on clearSongDownloads", e)
        }
        DownloadTracker.clearAll()
        DownloadTracker.init(context)
    }

    suspend fun calculateCacheSizes(cacheLocation: String): CacheBreakdown = withContext(Dispatchers.IO) {
        val playbackBytes = getDirectorySize(File(context.cacheDir, "media_playback_buffer"))
        val coverArtBytes = getDirectorySize(File(context.cacheDir, "image_cache"))
        
        val privatePersistentDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache")
        val privateExportedDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
        val songBytes = getDirectorySize(privatePersistentDir) + getDirectorySize(privateExportedDir)
        
        var externalBytes = 0L
        
        val totalCache = getDirectorySize(context.cacheDir)
        val totalFiles = getDirectorySize(context.filesDir)
        val totalExternalFiles = getDirectorySize(context.getExternalFilesDir(null))
        val totalPrivate = totalCache + totalFiles + totalExternalFiles
        val otherBytes = (totalPrivate - playbackBytes - coverArtBytes - songBytes).coerceAtLeast(0L)

        if (cacheLocation.isNotBlank()) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, cacheLocation.toUri())
                rootDoc?.listFiles()?.forEach { file ->
                    if (de.lwp2070809.speculonic.util.FormatUtils.isSupportedAudioFile(file.name ?: "")) {
                        externalBytes += file.length()
                    }
                }
            } catch (e: Exception) {
                LogManager.w("CacheOperations: Failed to calculate external cache size")
            }
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
