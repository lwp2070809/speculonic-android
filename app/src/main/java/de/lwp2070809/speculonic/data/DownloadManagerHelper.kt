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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@AndroidxOptIn(UnstableApi::class)
object DownloadManagerHelper {
    private data class BuiltDownloadManager(
        val manager: DownloadManager,
        val executor: ExecutorService
    )

    @Volatile private var downloadExecutor: ExecutorService? = null
    @Volatile private var deferredHolder = CompletableDeferred<DownloadManager>()
    private var initJob: Job? = null
    private val initGeneration = AtomicLong(0L)

    @Suppress("TooGenericExceptionCaught")
    fun initializeAsync(context: Context) {
        synchronized(this) {
            val current = deferredHolder
            if (current.isCompleted && !current.isCancelled) return
            if (initJob?.isActive == true) return
            val targetDeferred = if (current.isCancelled || current.isCompleted) {
                CompletableDeferred<DownloadManager>().also { deferredHolder = it }
            } else {
                current
            }
            val generation = initGeneration.get()
            initJob = CoroutineScope(Dispatchers.IO).launch {
                var built: BuiltDownloadManager? = null
                try {
                    built = buildDownloadManager(context, generation)
                    val accepted = synchronized(this@DownloadManagerHelper) {
                        if (generation == initGeneration.get() &&
                            deferredHolder === targetDeferred &&
                            !targetDeferred.isCompleted
                        ) {
                            downloadExecutor = built.executor
                            targetDeferred.complete(built.manager)
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        built.manager.release()
                        built.executor.shutdownNow()
                        built = null
                        targetDeferred.cancel()
                    }
                } catch (e: CancellationException) {
                    built?.manager?.release()
                    built?.executor?.shutdownNow()
                    targetDeferred.cancel(e)
                    throw e
                } catch (e: Exception) {
                    LogManager.e("DownloadManagerHelper: Failed to build DownloadManager", e)
                    targetDeferred.completeExceptionally(e)
                } finally {
                    synchronized(this@DownloadManagerHelper) {
                        if (generation == initGeneration.get()) {
                            initJob = null
                        }
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun getDownloadManager(context: Context): DownloadManager {
        val current = deferredHolder
        if (current.isCompleted && !current.isCancelled) {
            return current.completedManagerOrNull() ?: fallbackInit(context)
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
        if (current.isCompleted && !current.isCancelled) {
            current.completedManagerOrNull()?.let { return it }
            val completionError = current.getCompletionExceptionOrNull()
            if (completionError is CancellationException) throw completionError
            return withContext(Dispatchers.IO) { fallbackInit(context) }
        }
        initializeAsync(context)
        return try {
            deferredHolder.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                initializeAsync(context)
                deferredHolder.await()
            } else {
                throw e
            }
        }
    }

    fun getDownloadManagerOnlyIfInitialized(): DownloadManager? =
        deferredHolder.completedManagerOrNull()

    private fun CompletableDeferred<DownloadManager>.completedManagerOrNull(): DownloadManager? =
        if (isCompleted && !isCancelled && getCompletionExceptionOrNull() == null) {
            getCompleted()
        } else {
            null
        }

    private fun fallbackInit(context: Context): DownloadManager {
        val (generation, fresh) = synchronized(this) {
            val cur = deferredHolder
            if (cur.isCompleted && !cur.isCancelled) {
                cur.completedManagerOrNull()?.let { return it }
            }
            Pair(
                initGeneration.get(),
                CompletableDeferred<DownloadManager>().also { deferredHolder = it }
            )
        }
        val built = executeFallbackBuild(context, generation, fresh)
        val accepted = synchronized(this) {
            if (generation == initGeneration.get() && deferredHolder === fresh && !fresh.isCompleted) {
                downloadExecutor = built.executor
                fresh.complete(built.manager)
            } else {
                false
            }
        }
        if (!accepted) {
            built.manager.release()
            built.executor.shutdownNow()
            fresh.cancel()
            throw CancellationException("DownloadManagerHelper released during fallbackInit")
        }
        return built.manager
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeFallbackBuild(
        context: Context,
        generation: Long,
        fresh: CompletableDeferred<DownloadManager>
    ): BuiltDownloadManager = try {
        runBlocking(Dispatchers.IO) { buildDownloadManager(context, generation) }
    } catch (e: CancellationException) {
        fresh.cancel(e)
        throw e
    } catch (e: Exception) {
        fresh.completeExceptionally(e)
        throw e
    }

    private suspend fun buildDownloadManager(context: Context, generation: Long): BuiltDownloadManager =
        withContext(Dispatchers.IO) {
            val preferencesManager = PreferencesManager.getInstance(context)
            val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()

            val downloadCache = CacheManager.acquireDownloadCache(context)
            if (generation != initGeneration.get()) {
                throw CancellationException("DownloadManagerHelper released before acquiring databaseProvider")
            }

            val okHttpClient = NetworkModule.provideStreamOkHttpClient(context)
            val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val upstreamDataSourceFactory = httpDataSourceFactory

            val databaseProvider = CacheManager.acquireDatabaseProvider(context)
            if (generation != initGeneration.get()) {
                throw CancellationException("DownloadManagerHelper released before constructing DownloadManager")
            }

            val newExecutor = Executors.newFixedThreadPool(4)

            LogManager.i("Initializing DownloadManager for Persistent Track with Cascade Read. mobilePlayAllowed=$mobilePlayAllowed")

            val manager = DownloadManager(
                context.applicationContext,
                databaseProvider,
                downloadCache,
                upstreamDataSourceFactory,
                newExecutor
            ).apply {
                maxParallelDownloads = 5

                // 避免 Media3 强制依赖系统 NET_CAPABILITY_VALIDATED 导致自托管内网或无公网验证环境下任务永久排队阻塞
                requirements = Requirements(0)

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
                            currentState == Download.STATE_REMOVING
                        ) {
                            lastStates.remove(id)
                        }
                    }
                })

                resumeDownloads()
            }
            BuiltDownloadManager(manager, newExecutor)
        }

    fun updateRequirements(mobilePlayAllowed: Boolean) {
        val manager = getDownloadManagerOnlyIfInitialized() ?: return
        LogManager.i("Updating DownloadManager requirements: mobilePlayAllowed=$mobilePlayAllowed (Requirements set to 0)")
        manager.requirements = Requirements(0)
    }

    fun release() {
        LogManager.i("Releasing DownloadManagerHelper resources.")
        val (manager, executor) = synchronized(this) {
            initGeneration.incrementAndGet()
            initJob?.cancel()
            initJob = null
            val activeManager = getDownloadManagerOnlyIfInitialized()
            val activeExecutor = downloadExecutor
            downloadExecutor = null
            deferredHolder.cancel()
            deferredHolder = CompletableDeferred()
            Pair(activeManager, activeExecutor)
        }
        manager?.release()
        executor?.shutdown()
    }
}
