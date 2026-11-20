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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@AndroidxOptIn(UnstableApi::class)
@Singleton
class DownloadManagerHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val cacheManager: CacheManager
) {
    @Volatile private var downloadExecutor: ExecutorService? = null
    @Volatile private var deferredHolder = CompletableDeferred<DownloadManager>()
    private var initJob: Job? = null
    private val initGeneration = AtomicLong(0L)

    @Suppress("TooGenericExceptionCaught")
    fun initializeAsync() {
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
                try {
                    buildAndPublishDownloadManager(generation, targetDeferred)
                } catch (e: CancellationException) {
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
    fun getDownloadManager(): DownloadManager {
        val current = deferredHolder
        if (current.isCompleted && !current.isCancelled) {
            return current.completedManagerOrNull() ?: fallbackInit()
        }
        val pending = initializeAndGetDeferred()
        return try {
            runBlocking(Dispatchers.IO) {
                pending.await()
            }
        } catch (e: Exception) {
            LogManager.e("DownloadManagerHelper: Error awaiting DownloadManager in getDownloadManager", e)
            fallbackInit()
        }
    }

    suspend fun getDownloadManagerSuspend(): DownloadManager {
        val current = deferredHolder
        if (current.isCompleted && !current.isCancelled) {
            current.completedManagerOrNull()?.let { return it }
            val completionError = current.getCompletionExceptionOrNull()
            if (completionError is CancellationException) throw completionError
            return withContext(Dispatchers.IO) { fallbackInit() }
        }
        val pending = initializeAndGetDeferred()
        return try {
            pending.await()
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                initializeAndGetDeferred().await()
            } else {
                throw e
            }
        }
    }

    fun getDownloadManagerOnlyIfInitialized(): DownloadManager? =
        deferredHolder.completedManagerOrNull()

    // 与 release() 替换 holder 互斥，避免启动旧代初始化后却等待一个尚未启动的新代 holder。
    private fun initializeAndGetDeferred(): CompletableDeferred<DownloadManager> = synchronized(this) {
        initializeAsync()
        deferredHolder
    }

    private fun CompletableDeferred<DownloadManager>.completedManagerOrNull(): DownloadManager? =
        if (isCompleted && !isCancelled && getCompletionExceptionOrNull() == null) {
            getCompleted()
        } else {
            null
        }

    private fun fallbackInit(): DownloadManager {
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
        return executeFallbackBuild(generation, fresh)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeFallbackBuild(
        generation: Long,
        fresh: CompletableDeferred<DownloadManager>
    ): DownloadManager = try {
        runBlocking(Dispatchers.IO) { buildAndPublishDownloadManager(generation, fresh) }
    } catch (e: CancellationException) {
        fresh.cancel(e)
        throw e
    } catch (e: Exception) {
        fresh.completeExceptionally(e)
        throw e
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun buildAndPublishDownloadManager(
        generation: Long,
        targetDeferred: CompletableDeferred<DownloadManager>
    ): DownloadManager =
        withContext(Dispatchers.IO) {
            val mobilePlayAllowed = preferencesManager.mobilePlayAllowed.first()
            val okHttpClient = NetworkModule.provideStreamOkHttpClient(context)
            val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val initializationContext = currentCoroutineContext()

            // 资源获取、构造和发布共享清理使用的锁，避免已取得的缓存先被释放，或新实例尚未发布就被清理遗漏。
            cacheManager.withDownloadResources { downloadCache, databaseProvider ->
                initializationContext.ensureActive()
                if (!isCurrentInitialization(generation, targetDeferred)) {
                    throw CancellationException("DownloadManagerHelper initialization superseded before construction")
                }
                val newExecutor = Executors.newFixedThreadPool(4)
                var createdManager: DownloadManager? = null
                try {
                    LogManager.i(
                        "Initializing DownloadManager for Persistent Track with Cascade Read. mobilePlayAllowed=$mobilePlayAllowed"
                    )
                    val manager = DownloadManager(
                        context.applicationContext,
                        databaseProvider,
                        downloadCache,
                        httpDataSourceFactory,
                        newExecutor
                    )
                    createdManager = manager
                    configureDownloadManager(manager)
                    initializationContext.ensureActive()
                    if (!publishIfCurrent(generation, targetDeferred, manager, newExecutor)) {
                        throw CancellationException("DownloadManagerHelper initialization superseded before publication")
                    }
                    manager
                } catch (e: Throwable) {
                    runCatching { createdManager?.release() }.exceptionOrNull()?.let(e::addSuppressed)
                    newExecutor.shutdownNow()
                    throw e
                }
            }
        }

    private fun isCurrentInitialization(generation: Long, targetDeferred: CompletableDeferred<DownloadManager>): Boolean =
        synchronized(this) {
            generation == initGeneration.get() && deferredHolder === targetDeferred && !targetDeferred.isCompleted
        }

    private fun publishIfCurrent(
        generation: Long,
        targetDeferred: CompletableDeferred<DownloadManager>,
        manager: DownloadManager,
        executor: ExecutorService
    ): Boolean = synchronized(this) {
        if (!isCurrentInitialization(generation, targetDeferred) || !targetDeferred.complete(manager)) {
            false
        } else {
            downloadExecutor = executor
            true
        }
    }

    private fun configureDownloadManager(manager: DownloadManager) {
        manager.maxParallelDownloads = MAX_PARALLEL_DOWNLOADS

        // 避免 Media3 强制依赖系统 NET_CAPABILITY_VALIDATED 导致自托管内网或无公网验证环境下任务永久排队阻塞
        manager.requirements = Requirements(0)

        manager.addListener(object : DownloadManager.Listener {
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

        manager.resumeDownloads()
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

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 5
    }
}
