package de.lwp2070809.speculonic.domain.repository

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.util.CoverArtKeyUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext


class CoverArtSyncManager(
    private val context: Context,
    private val baseUrl: String,
    private val musicDao: MusicDao,
    private val urlBuilder: UrlBuilder
) {
    fun getCoverArtCacheKey(id: String): String {
        val normalizedBaseUrl = CoverArtKeyUtils.normalizeBaseUrl(baseUrl)
        return "cover_${normalizedBaseUrl.hashCode()}_$id"
    }

    fun buildCoverArtRequest(id: String?, preferLocal: Boolean = false, size: Int? = null, crossfade: Boolean = true): ImageRequest {
        val requestBuilder = ImageRequest.Builder(context)
            .data(id?.let { urlBuilder.buildCoverArtUrl(it) })
            .crossfade(crossfade)
        
        if (size != null) {
            
            requestBuilder.size(size)
        }

        if (id != null) {
            val stableKey = getCoverArtCacheKey(id)
            
            requestBuilder.diskCacheKey(stableKey)
            
            requestBuilder.memoryCacheKey(if (size != null) "${stableKey}_$size" else stableKey)
        }

        if (preferLocal) {
            requestBuilder.diskCachePolicy(CachePolicy.ENABLED)
            requestBuilder.networkCachePolicy(CachePolicy.ENABLED)
        }
        
        return requestBuilder.build()
    }

    
    suspend fun syncAllCoverArt(onProgress: (suspend (String) -> Unit)? = null) = withContext(Dispatchers.IO) {
        val ids = musicDao.getAllUniqueCoverArtIds()
        if (ids.isEmpty()) return@withContext

        LogManager.i("CoverArtSyncManager: Starting cover art pre-fetch for ${ids.size} unique IDs (Full resolution)")
        val imageLoader = SingletonImageLoader.get(context)
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val skipCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val total = ids.size

        ids.chunked(20).forEach { chunk ->
            kotlinx.coroutines.coroutineScope {
                chunk.map { id ->
                    async {
                        val stableKey = getCoverArtCacheKey(id)
                        val isCached = imageLoader.diskCache?.openSnapshot(stableKey)?.use { true } ?: false

                        if (isCached) {
                            skipCount.incrementAndGet()
                        } else {
                            val url = urlBuilder.buildCoverArtUrl(id)
                            val request = ImageRequest.Builder(context)
                                .data(url)
                                .diskCacheKey(stableKey)
                                .memoryCacheKey(stableKey)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .networkCachePolicy(CachePolicy.ENABLED)
                                .build()

                            try {
                                
                                
                                val result = kotlinx.coroutines.withTimeout(30_000L) {
                                    imageLoader.execute(request)
                                }
                                if (result is ErrorResult) {
                                    failCount.incrementAndGet()
                                    val errorMessage = result.throwable.message ?: "Unknown error"
                                    if (errorMessage.contains("unimplemented") || errorMessage.contains("Input contained an error")) {
                                        LogManager.w("CoverArtSyncManager: Cover art $id could not be decoded (likely malformed or unsupported format): $errorMessage")
                                    } else {
                                        LogManager.w("CoverArtSyncManager: Failed to pre-fetch cover art $id: $errorMessage")
                                    }
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                
                                failCount.incrementAndGet()
                                LogManager.w("CoverArtSyncManager: Timeout while fetching cover art $id. Skipping.")
                            }
                        }

                        
                        
                        val current = count.incrementAndGet()
                        onProgress?.invoke("正在同步专辑封面 ($current/$total)")
                    }
                }.awaitAll()
            }
        }
        LogManager.i("CoverArtSyncManager: Cover art pre-fetch completed (${count.get()}/$total, skipped ${skipCount.get()}, failed ${failCount.get()})")
    }
}
