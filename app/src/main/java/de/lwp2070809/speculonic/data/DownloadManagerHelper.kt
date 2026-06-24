package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.util.LogManager

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object DownloadManagerHelper {
    private var downloadManager: DownloadManager? = null
    private var downloadExecutor: java.util.concurrent.ExecutorService? = null

    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun initializeAsync(context: Context) {
        if (downloadManager != null) return
        if (isInitializing.getAndSet(true)) return
        try {
            val preferencesManager = PreferencesManager.getInstance(context)
            val maxCacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                preferencesManager.maxCacheSize.first()
            }
            val mobilePlayAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                preferencesManager.mobilePlayAllowed.first()
            }
            synchronized(this) {
                if (downloadManager == null) {
                    initialize(context, maxCacheSize, mobilePlayAllowed)
                }
            }
        } finally {
            isInitializing.set(false)
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: synchronized(this) {
            downloadManager ?: run {
                LogManager.w("DownloadManagerHelper: getDownloadManager called before async init, initializing with defaults")
                val defaultCacheSize = 1024L * 1024 * 1024 // 1GB
                initialize(context, defaultCacheSize, mobilePlayAllowed = true)
                downloadManager!!
            }
        }
    }

    suspend fun getDownloadManagerSuspend(context: Context): DownloadManager {
        if (downloadManager == null) {
            initializeAsync(context)
        }
        return downloadManager!!
    }

    private fun initialize(context: Context, maxCacheSize: Long, mobilePlayAllowed: Boolean) {
        
        val downloadCache = CacheManager.getDownloadCache(context, maxCacheSize)
        val playbackCache = CacheManager.getPlaybackCache(context)
        
        val okHttpClient = NetworkModule.provideOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        
        
        
        val upstreamDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheWriteDataSinkFactory(null) 
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val databaseProvider = CacheManager.getDatabaseProvider(context)
        
        
        val newExecutor = Executors.newFixedThreadPool(4)
        downloadExecutor = newExecutor
        
        LogManager.i("Initializing DownloadManager for Persistent Track with Cascade Read. mobilePlayAllowed=$mobilePlayAllowed")
        
        downloadManager = DownloadManager(
            context.applicationContext,
            databaseProvider,
            downloadCache,
            httpDataSourceFactory,
            newExecutor
        ).apply {
            maxParallelDownloads = 5 
            
            requirements = if (mobilePlayAllowed) {
                Requirements(Requirements.NETWORK)
            } else {
                Requirements(Requirements.NETWORK_UNMETERED)
            }
            
            addListener(object : DownloadManager.Listener {
                private val lastStates = java.util.concurrent.ConcurrentHashMap<String, Int>()

                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    val id = download.request.id
                    val currentState = download.state
                    val lastState = lastStates[id]

                    if (lastState != currentState || finalException != null) {
                        lastStates[id] = currentState
                        val stateStr = when (currentState) {
                            Download.STATE_QUEUED -> "QUEUED"
                            Download.STATE_STOPPED -> "STOPPED"
                            Download.STATE_DOWNLOADING -> "DOWNLOADING"
                            Download.STATE_COMPLETED -> "COMPLETED"
                            Download.STATE_FAILED -> "FAILED"
                            Download.STATE_REMOVING -> "REMOVING"
                            Download.STATE_RESTARTING -> "RESTARTING"
                            else -> "UNKNOWN ($currentState)"
                        }
                        val exMsg = if (finalException != null) ", exception: ${finalException.message}" else ""
                        LogManager.d("DownloadManager [$id] state changed -> $stateStr$exMsg")
                    }

                    
                    if (currentState == Download.STATE_COMPLETED || 
                        currentState == Download.STATE_FAILED || 
                        currentState == Download.STATE_REMOVING) {
                        lastStates.remove(id)
                    }
                }
            })
            
            resumeDownloads()
        }
    }

    
    fun updateRequirements(mobilePlayAllowed: Boolean) {
        synchronized(this) {
            LogManager.i("Updating DownloadManager requirements: mobilePlayAllowed=$mobilePlayAllowed")
            val requirements = if (mobilePlayAllowed) {
                Requirements(Requirements.NETWORK)
            } else {
                Requirements(Requirements.NETWORK_UNMETERED)
            }
            downloadManager?.requirements = requirements
        }
    }

    fun release() {
        synchronized(this) {
            LogManager.i("Releasing DownloadManagerHelper resources.")
            downloadManager?.release()
            downloadManager = null
            downloadExecutor?.shutdown()
            downloadExecutor = null
        }
    }
    
    fun reset() {
        release()
    }
}
