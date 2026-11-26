package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.util.LogManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManagerHelper: dagger.Lazy<DownloadManagerHelper>
) {
    private val mutex = Mutex()
    private val releaseMutex = Mutex()
    @Volatile private var playbackCache: Cache? = null
    @Volatile private var downloadCache: Cache? = null
    @Volatile private var databaseProvider: DatabaseProvider? = null
    @Volatile private var currentDownloadCacheSize: Long = -2L
    @Volatile var isReleasing: Boolean = false
        private set

    @Volatile var onRequireCacheRelease: (suspend (preservePlayback: Boolean) -> Unit)? = null
    @Volatile var onCacheRebuild: (() -> Unit)? = null

    private fun getDatabaseProviderLocked(): DatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
            databaseProvider = it
        }
    }

    private fun getPlaybackCacheLocked(): Cache {
        if (playbackCache == null) {
            val cacheDir = File(context.cacheDir, "media_playback_buffer")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024L)
            playbackCache = SimpleCache(cacheDir, evictor, getDatabaseProviderLocked())
            LogManager.i("Playback Stream Buffer initialized at: ${cacheDir.absolutePath}")
        }
        return playbackCache!!
    }

    private fun getDownloadCacheLocked(): Cache {
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
                downloadCache = SimpleCache(targetDir, evictor, getDatabaseProviderLocked())
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
    suspend fun acquirePlaybackCache(): Cache = mutex.withLock {
        getPlaybackCacheLocked()
    }

    suspend fun acquireDownloadCache(): Cache = mutex.withLock {
        getDownloadCacheLocked()
    }

    suspend fun acquireDatabaseProvider(): DatabaseProvider = mutex.withLock {
        getDatabaseProviderLocked()
    }

    internal suspend fun <T> withDownloadResources(block: (Cache, Cache, DatabaseProvider) -> T): T = mutex.withLock {
        block(getDownloadCacheLocked(), getPlaybackCacheLocked(), getDatabaseProviderLocked())
    }

    fun getPlaybackCacheOrNull(): Cache? = if (isReleasing) null else playbackCache

    fun getDownloadCacheOrNull(): Cache? = if (isReleasing) null else downloadCache

    suspend fun executeWithCacheReleaseLock(preservePlayback: Boolean = true, block: () -> Unit) {
        releaseMutex.withLock {
            isReleasing = true
            try {
                LogManager.i("CacheManager: Requesting active components to release caches...")
                // 主线程可能正在同步等待下载初始化；回调期间必须允许它取得缓存资源。
                onRequireCacheRelease?.invoke(preservePlayback)
                mutex.withLock {
                    downloadManagerHelper.get().release()
                    clearDownloadManagerHelpers()
                    releaseCaches()
                    block()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LogManager.e("CacheManager: Error during cache release & clearance", e)
                throw e
            } finally {
                isReleasing = false
                rebuildCaches()
            }
        }
    }

    private fun clearDownloadManagerHelpers() {
        try {
            androidx.media3.exoplayer.offline.DownloadService.clearDownloadManagerHelpers()
            LogManager.i("CacheManager: Cleared Media3 DownloadManagerHelpers static cache.")
        } catch (e: Exception) {
            LogManager.e("CacheManager: Failed to clear DownloadManagerHelpers", e)
        }
    }

    private suspend fun releaseCaches() {
        var retryCount = 0
        var allReleased = false
        while (retryCount < MAX_CACHE_RELEASE_RETRIES && !allReleased) {
            var attemptSuccess = true
            playbackCache?.let { cache ->
                try {
                    cache.release()
                    playbackCache = null
                } catch (e: Exception) {
                    attemptSuccess = false
                    LogManager.w(
                        "CacheManager: Failed to release playbackCache (attempt ${retryCount + 1}/$MAX_CACHE_RELEASE_RETRIES)", e
                    )
                }
            }
            downloadCache?.let { cache ->
                try {
                    cache.release()
                    downloadCache = null
                } catch (e: Exception) {
                    attemptSuccess = false
                    LogManager.w(
                        "CacheManager: Failed to release downloadCache (attempt ${retryCount + 1}/$MAX_CACHE_RELEASE_RETRIES)", e
                    )
                }
            }
            if (attemptSuccess) {
                databaseProvider = null
                LogManager.i("CacheManager: All cache instances and database provider released.")
                allReleased = true
            } else {
                retryCount++
                if (retryCount >= MAX_CACHE_RELEASE_RETRIES) {
                    LogManager.e("CacheManager: Some caches failed to release after $retryCount retries, forcing clearance.")
                    playbackCache = null
                    downloadCache = null
                    databaseProvider = null
                } else {
                    delay(CACHE_RELEASE_RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun rebuildCaches() {
        try {
            onCacheRebuild?.invoke()
        } catch (e: Exception) {
            LogManager.e("CacheManager: Error in onCacheRebuild callback", e)
        }
    }

    private companion object {
        const val MAX_CACHE_RELEASE_RETRIES = 3
        const val CACHE_RELEASE_RETRY_DELAY_MS = 100L
    }
}
