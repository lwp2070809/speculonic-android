package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.DownloadManagerHelper
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
        try {
            val downloadManager = DownloadManagerHelper.getDownloadManagerSuspend(context)
            downloadManager.removeAllDownloads()
        } catch (e: Exception) {
            LogManager.e("CacheOperations: Failed to clear DownloadManager tasks", e)
        }
        
        DownloadManagerHelper.reset()

        CacheManager.executeWithCacheReleaseLock {
            File(context.cacheDir, "media_playback_buffer").deleteRecursively()
            File(context.cacheDir, "image_cache").deleteRecursively()
            val internalPersistentDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache")
            internalPersistentDir.deleteRecursively()
        }
    }

    suspend fun calculateCacheSizes(cacheLocation: String): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var internalBytes = 0L
        var externalBytes = 0L
        
        internalBytes += getDirectorySize(File(context.cacheDir, "media_playback_buffer"))
        internalBytes += getDirectorySize(File(context.cacheDir, "image_cache"))
        internalBytes += getDirectorySize(File(context.getExternalFilesDir(null) ?: context.filesDir, "media_persistent_cache"))
        
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
        
        internalBytes to externalBytes
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
