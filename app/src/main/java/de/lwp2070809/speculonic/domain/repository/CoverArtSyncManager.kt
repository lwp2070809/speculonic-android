package de.lwp2070809.speculonic.domain.repository

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.util.CoverArtKeyUtils
import de.lwp2070809.speculonic.util.EmbeddedCoverFallback
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CoverArtSyncManager(
    private val context: Context,
    private val baseUrl: String,
    private val musicDao: MusicDao,
    private val urlBuilder: UrlBuilder,
    private val getImageLoader: () -> ImageLoader = { SingletonImageLoader.get(context) }
) {
    fun getCoverArtCacheKey(id: String): String {
        return CoverArtKeyUtils.buildStableKey(baseUrl, id)
    }

    fun buildCoverArtRequest(
        id: String?,
        size: Int? = null,
        crossfade: Boolean = true,
        fallbackUri: android.net.Uri? = null
    ): ImageRequest {
        val cleanedFallback = fallbackUri?.toString()
        val requestData: Any? = id?.let { urlBuilder.buildCoverArtUrl(it) } ?: cleanedFallback

        val requestBuilder = ImageRequest.Builder(context)
            .data(requestData)
            .crossfade(crossfade)

        if (size != null) {
            requestBuilder.size(size)
        }

        val stableKey = when {
            id != null -> getCoverArtCacheKey(id)
            cleanedFallback != null -> CoverArtKeyUtils.buildStableKey(cleanedFallback)
            else -> null
        }
        if (stableKey != null) {
            requestBuilder.diskCacheKey(stableKey)
            requestBuilder.memoryCacheKey(if (size != null) "${stableKey}_$size" else stableKey)
        }

        return requestBuilder.build()
    }

    
    private val prefetchMutex = Mutex()

    companion object {
        private val lifecycle = Mutex()
        private val pending = mutableSetOf<Job>()

        internal suspend fun clear(loader: ImageLoader) = lifecycle.withLock {
            val old = synchronized(pending) { pending.toList() }
            old.forEach { it.cancel() }
            old.joinAll()
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        }
    }

    // 返回本地化的提前停止原因，供手动同步与后台任务展示。
    suspend fun syncAllCoverArt(onProgress: (suspend (String) -> Unit)? = null): String? =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val job = currentCoroutineContext().job
                lifecycle.withLock { synchronized(pending) { pending.add(job) } }
                try {
                    prefetchMutex.withLock { sync(onProgress) }
                } finally {
                    synchronized(pending) { pending.remove(job) }
                }
            }
        }

    private suspend fun sync(onProgress: (suspend (String) -> Unit)?): String? {
        val ids = musicDao.getAllUniqueCoverArtIds()
        val loader = getImageLoader()
        val diskCache = checkNotNull(loader.diskCache)
        var processed = 0
        var failed = 0
        var embedded = 0
        for (batch in ids.asSequence().chunked(4)) {
            if (diskCache.size >= diskCache.maxSize - diskCache.maxSize / 10) {
                val reason = context.getString(R.string.cover_sync_quota, processed, ids.size)
                LogManager.i("CoverArtSync: $reason")
                onProgress?.invoke(reason)
                return reason
            }
            val results = coroutineScope {
                batch.map { id -> async { prefetch(loader, id) } }.awaitAll()
            }
            processed += batch.size
            failed += results.count { it == PrefetchResult.FAILED }
            embedded += results.count { it == PrefetchResult.EMBEDDED }
            onProgress?.invoke(context.getString(R.string.cover_sync_progress, processed, ids.size))
        }
        LogManager.i("CoverArtSync: processed=$processed, embedded=$embedded, failed=$failed")
        return null
    }

    private enum class PrefetchResult { DISK, EMBEDDED, FAILED }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun prefetch(loader: ImageLoader, id: String): PrefetchResult {
        val key = getCoverArtCacheKey(id)
        val disk = checkNotNull(loader.diskCache)
        if (disk.openSnapshot(key)?.use { true } == true) return PrefetchResult.DISK
        val request = ImageRequest.Builder(context)
            .data(urlBuilder.buildCoverArtUrl(id))
            .diskCacheKey(key)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(BlackholeDecoder.Factory())
            .apply { extras[EmbeddedCoverFallback.prefetchKey] = true }
            .build()
        return try {
            when (val result = withTimeoutOrNull(30_000L) { loader.execute(request) }) {
                is SuccessResult -> when {
                    disk.openSnapshot(key)?.use { true } == true -> PrefetchResult.DISK
                    result.dataSource == DataSource.MEMORY -> PrefetchResult.EMBEDDED
                    else -> PrefetchResult.FAILED
                }
                else -> {
                    val reason = (result as? ErrorResult)?.throwable?.message ?: "timeout"
                    LogManager.w("CoverArtSync: failed to fetch $id: $reason")
                    PrefetchResult.FAILED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.w("CoverArtSync: failed to fetch $id", e)
            PrefetchResult.FAILED
        }
    }
}
