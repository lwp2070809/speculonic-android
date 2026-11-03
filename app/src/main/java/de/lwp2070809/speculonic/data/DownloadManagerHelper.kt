@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package de.lwp2070809.speculonic.data

import android.content.Context
import kotlin.OptIn
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@AndroidxOptIn(UnstableApi::class)
object DownloadManagerHelper {
    @Volatile private var downloadExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var deferredHolder = CompletableDeferred<DownloadManager>()
    @Volatile private var isInitializing = false

    fun initializeAsync(context: Context) {
        val currentDeferred = deferredHolder
        if (currentDeferred.isCompleted) return
        synchronized(this) {
            if (isInitializing || deferredHolder.isCompleted) return
            isInitializing = true
        }
        val targetDeferred = deferredHolder
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = buildDownloadManager(context)
                targetDeferred.complete(manager)
            } catch (e: Exception) {
                LogManager.e("DownloadManagerHelper: Failed to build DownloadManager", e)
                targetDeferred.completeExceptionally(e)
            } finally {
                synchronized(this@DownloadManagerHelper) {
                    isInitializing = false
                }
            }
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        val current = deferredHolder
        if (current.isCompleted) {
            return try {
                current.getCompleted()
            } catch (e: Exception) {
                fallbackInit(context)
            }
        }
        initializeAsync(context)
        return try {
            runBlocking(Dispatchers.IO) {
                deferredHolder.await()
            }
        } catch (e: Exception) {
            LogManager.e("DownloadManagerHelper: Error awaiting DownloadManager in getDownloadManager", e)
            fallbackInit(context)
        }
    }

    suspend fun getDownloadManagerSuspend(context: Context): DownloadManager {
        val current = deferredHolder
        if (current.isCompleted) {
            return try {
                current.getCompleted()
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { fallbackInit(context) }
            }
        }
        initializeAsync(context)
        return deferredHolder.await()
    }

    fun getDownloadManagerOnlyIfInitialized(): DownloadManager? {
        val current = deferredHolder
        return if (current.isCompleted) {
            try {
                current.getCompleted()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun fallbackInit(context: Context): DownloadManager {
        synchronized(this) {
            val cur = deferredHolder
            if (cur.isCompleted) {
                try {
                    return cur.getCompleted()
                } catch (_: Exception) {}
            }
            deferredHolder = CompletableDeferred()
            val manager = runBlocking(Dispatchers.IO) {
                buildDownloadManager(context)
            }
            deferredHolder.complete(manager)
            return manager
        }
    }

    private suspend fun buildDownloadManager(context: Context): DownloadManager = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()

        val downloadCache = CacheManager.getDownloadCache(context)
        val playbackCache = CacheManager.getPlaybackCache(context)

        val okHttpClient = NetworkModule.provideStreamOkHttpClient(context)
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val upstreamDataSourceFactory = httpDataSourceFactory

        val databaseProvider = CacheManager.getDatabaseProvider(context)

        val newExecutor = Executors.newFixedThreadPool(4)
        downloadExecutor = newExecutor

        LogManager.i("Initializing DownloadManager for Persistent Track with Cascade Read. mobilePlayAllowed=$mobilePlayAllowed")

        DownloadManager(
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
        val manager = getDownloadManagerOnlyIfInitialized() ?: return
        LogManager.i("Updating DownloadManager requirements: mobilePlayAllowed=$mobilePlayAllowed")
        val requirements = if (mobilePlayAllowed) {
            Requirements(Requirements.NETWORK)
        } else {
            Requirements(Requirements.NETWORK_UNMETERED)
        }
        manager.requirements = requirements
    }

    fun release() {
        LogManager.i("Releasing DownloadManagerHelper resources.")
        val manager = getDownloadManagerOnlyIfInitialized()
        manager?.release()
        downloadExecutor?.shutdown()
        downloadExecutor = null
        synchronized(this) {
            deferredHolder = CompletableDeferred()
            isInitializing = false
        }
    }
}
