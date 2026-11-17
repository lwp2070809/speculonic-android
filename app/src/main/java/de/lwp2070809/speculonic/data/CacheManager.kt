package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import de.lwp2070809.speculonic.util.LogManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
object CacheManager {
    private val mutex = Mutex()
    @Volatile private var playbackCache: Cache? = null
    @Volatile private var downloadCache: Cache? = null
    @Volatile private var databaseProvider: DatabaseProvider? = null
    @Volatile private var currentDownloadCacheSize: Long = -2L
    @Volatile var isReleasing: Boolean = false
        private set

    @Volatile var onRequireCacheRelease: (suspend () -> Unit)? = null
    @Volatile var onCacheRebuild: (() -> Unit)? = null

    private fun getDatabaseProviderLocked(context: Context): DatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    private fun getPlaybackCacheLocked(context: Context): Cache {
        if (playbackCache == null) {
            val cacheDir = File(context.cacheDir, "media_playback_buffer")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
            playbackCache = SimpleCache(cacheDir, evictor, getDatabaseProviderLocked(context))
            LogManager.i("Playback Stream Buffer initialized at: ${cacheDir.absolutePath}")
        }
        return playbackCache!!
    }

    private fun getDownloadCacheLocked(context: Context): Cache {
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
                downloadCache = SimpleCache(targetDir, evictor, getDatabaseProviderLocked(context))
                currentDownloadCacheSize = fixedCacheSize
                LogManager.i(
                    "Persistent Download Cache (Internal) initialized at: ${targetDir.absolutePath} with size $fixedCacheSize"
                )
            } catch (e: Exception) {
                LogManager.e("Failed to initialize Download Cache!", e)
                throw e
            }
        }
        return downloadCache!!
    }

    // 挂起获取缓存在清理锁释放前排队等待，避免释放瞬间抛 IllegalStateException 导致业务失败
    suspend fun acquirePlaybackCache(context: Context): Cache = mutex.withLock {
        getPlaybackCacheLocked(context)
    }

    suspend fun acquireDownloadCache(context: Context): Cache = mutex.withLock {
        getDownloadCacheLocked(context)
    }

    suspend fun acquireDatabaseProvider(context: Context): DatabaseProvider = mutex.withLock {
        getDatabaseProviderLocked(context)
    }

    fun getPlaybackCacheOrNull(): Cache? = if (isReleasing) null else playbackCache

    fun getDownloadCacheOrNull(): Cache? = if (isReleasing) null else downloadCache

    suspend fun executeWithCacheReleaseLock(block: () -> Unit) {
        try {
            mutex.withLock {
                isReleasing = true
                try {
                    LogManager.i("CacheManager: Requesting active components to release caches...")
                    onRequireCacheRelease?.invoke()

                    // 先释放持有同一 SimpleCache 的 DownloadManager，再释放底层 Cache
                    DownloadManagerHelper.release()

                    try {
                        androidx.media3.exoplayer.offline.DownloadService.clearDownloadManagerHelpers()
                        LogManager.i("CacheManager: Cleared Media3 DownloadManagerHelpers static cache.")
                    } catch (e: Exception) {
                        LogManager.e("CacheManager: Failed to clear DownloadManagerHelpers", e)
                    }

                    var retryCount = 0
                    while (retryCount < 3) {
                        var allSuccess = true
                        playbackCache?.let { cache ->
                            try {
                                cache.release()
                                playbackCache = null
                            } catch (e: Exception) {
                                allSuccess = false
                                LogManager.w(
                                    "CacheManager: Failed to release playbackCache (attempt ${retryCount + 1}/3)",
                                    e
                                )
                            }
                        }
                        downloadCache?.let { cache ->
                            try {
                                cache.release()
                                downloadCache = null
                            } catch (e: Exception) {
                                allSuccess = false
                                LogManager.w(
                                    "CacheManager: Failed to release downloadCache (attempt ${retryCount + 1}/3)",
                                    e
                                )
                            }
                        }

                        if (allSuccess) {
                            databaseProvider = null
                            LogManager.i("CacheManager: All cache instances and database provider released.")
                            break
                        }
                        retryCount++
                        if (retryCount >= 3) {
                            LogManager.e(
                                "CacheManager: Some caches failed to release after $retryCount retries, forcing clearance."
                            )
                            playbackCache = null
                            downloadCache = null
                            databaseProvider = null
                            break
                        }
                        delay(100)
                    }

                    block()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    LogManager.e("CacheManager: Error during cache release & clearance", e)
                    throw e
                } finally {
                    isReleasing = false
                }
            }
        } finally {
            try {
                onCacheRebuild?.invoke()
            } catch (e: Exception) {
                LogManager.e("CacheManager: Error in onCacheRebuild callback", e)
            }
        }
    }
}
