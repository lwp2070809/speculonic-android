package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import de.lwp2070809.speculonic.util.LogManager
import java.io.File
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
object CacheManager {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var playbackCache: Cache? = null
    @Volatile private var downloadCache: Cache? = null
    @Volatile private var databaseProvider: DatabaseProvider? = null
    @Volatile private var currentDownloadCacheSize: Long = -2L

    
    
    @Volatile var onRequireCacheRelease: (suspend () -> Unit)? = null
    @Volatile var onCacheRebuild: (() -> Unit)? = null

    @Synchronized
    fun getDatabaseProvider(context: Context): DatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    @Synchronized
    fun getPlaybackCache(context: Context): Cache {
        if (playbackCache == null) {
            val cacheDir = File(context.cacheDir, "media_playback_buffer")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
            playbackCache = SimpleCache(cacheDir, evictor, getDatabaseProvider(context))
            LogManager.i("Playback Stream Buffer initialized at: ${cacheDir.absolutePath}")
        }
        return playbackCache!!
    }

    @Synchronized
    fun getDownloadCache(context: Context): Cache {
        val fixedCacheSize = 900L * 1024 * 1024L // 900 MB
        if (downloadCache == null) {
            val externalDir = context.getExternalFilesDir(null)
            val targetDir = if (externalDir != null) {
                File(externalDir, "media_persistent_cache")
            } else {
                File(context.filesDir, "media_persistent_cache")
            }
            
            if (!targetDir.exists()) targetDir.mkdirs()
            try { 
                File(targetDir, ".nomedia").createNewFile() 
            } catch (e: java.io.IOException) {
                LogManager.e("Failed to create .nomedia file", e)
            }

            val evictor = LeastRecentlyUsedCacheEvictor(fixedCacheSize)
            
            try {
                downloadCache = SimpleCache(targetDir, evictor, getDatabaseProvider(context))
                currentDownloadCacheSize = fixedCacheSize
                LogManager.i("Persistent Download Cache (Internal) initialized at: ${targetDir.absolutePath} with size $fixedCacheSize")
            } catch (e: Exception) {
                LogManager.e("Failed to initialize Download Cache!", e)
                throw e
            }
        }
        return downloadCache!!
    }

    suspend fun executeWithCacheReleaseLock(block: () -> Unit) {
        mutex.withLock {
            try {
                LogManager.i("CacheManager: Requesting active components to release caches...")
                onRequireCacheRelease?.invoke()
                
                // Atomically release DownloadManager before releasing its underlying cache
                DownloadManagerHelper.release()
                
                try {
                    androidx.media3.exoplayer.offline.DownloadService.clearDownloadManagerHelpers()
                    LogManager.i("CacheManager: Cleared Media3 DownloadManagerHelpers static cache.")
                } catch (e: Exception) {
                    LogManager.e("CacheManager: Failed to clear DownloadManagerHelpers", e)
                }
                
                playbackCache?.release()
                playbackCache = null
                downloadCache?.release()
                downloadCache = null
                databaseProvider = null
                LogManager.i("CacheManager: All cache instances and database provider released.")
                
                block()
            } catch (e: Exception) {
                LogManager.e("CacheManager: Error during cache release & clearance", e)
            } finally {
                try {
                    onCacheRebuild?.invoke()
                } catch (e: Exception) {
                    LogManager.e("CacheManager: Error in onCacheRebuild callback", e)
                }
            }
        }
    }
}
