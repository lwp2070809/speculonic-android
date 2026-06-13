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

    
    fun getDownloadManager(context: Context): DownloadManager {
        return kotlinx.coroutines.runBlocking {
            mutex.withLock {
                if (downloadManager == null) {
                    val preferencesManager = PreferencesManager.getInstance(context)
                    val maxCacheSize = preferencesManager.maxCacheSize.first()
                    val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()
                    initialize(context, maxCacheSize, mobilePlayAllowed)
                }
                downloadManager!!
            }
        }
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    
    suspend fun getDownloadManagerSuspend(context: Context): DownloadManager {
        mutex.withLock {
            if (downloadManager == null) {
                val preferencesManager = PreferencesManager.getInstance(context)
                val maxCacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { preferencesManager.maxCacheSize.first() }
                val mobilePlayAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { preferencesManager.mobilePlayAllowed.first() }
                initialize(context, maxCacheSize, mobilePlayAllowed)
            }
            return downloadManager!!
        }
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
            upstreamDataSourceFactory,
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
                            Download.STATE_QUEUED -> "等待队列 (QUEUED)"
                            Download.STATE_STOPPED -> "暂停 (STOPPED)"
                            Download.STATE_DOWNLOADING -> "下载中 (DOWNLOADING)"
                            Download.STATE_COMPLETED -> "已完成 (COMPLETED)"
                            Download.STATE_FAILED -> "失败 (FAILED)"
                            Download.STATE_REMOVING -> "移除中 (REMOVING)"
                            Download.STATE_RESTARTING -> "重启中 (RESTARTING)"
                            else -> "未知状态 ($currentState)"
                        }
                        val exMsg = if (finalException != null) ", 异常: ${finalException.message}" else ""
                        LogManager.d("DownloadManager [$id] 状态变更 -> $stateStr$exMsg")
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
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                LogManager.i("Updating DownloadManager requirements: mobilePlayAllowed=$mobilePlayAllowed")
                val requirements = if (mobilePlayAllowed) {
                    Requirements(Requirements.NETWORK)
                } else {
                    Requirements(Requirements.NETWORK_UNMETERED)
                }
                downloadManager?.requirements = requirements
            }
        }
    }

    
    fun release() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                LogManager.i("Releasing DownloadManagerHelper resources.")
                downloadManager?.release()
                downloadManager = null
                downloadExecutor?.shutdown()
                downloadExecutor = null
            }
        }
    }
    
    fun reset() {
        release()
    }
}
