package de.lwp2070809.speculonic.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.util.CoverArtKeyUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面微件封面安全加载器：
 * 1. 严格遵守 Android 17 (API 37) RemoteViews 位图内存上限（RGB_565 编码 + 精确降采样）
 * 2. 优先本地物理文件 ID3 伴生嵌入封面，杜绝不必要的网络请求
 * 3. 针对 Coil 磁盘缓存与 Remote URL 进行安全加载与格式转换
 * 4. 具备复合缓存键（songId + artworkUri），封面变更立即失效
 * 5. 纯内存极速查询供 Compose 组合树使用，挂起加载在后台 IO 线程预热
 */
object WidgetCoverLoader {
    private const val TAG = "WidgetCoverLoader"
    private val coverBitmapCache = LruCache<String, Bitmap>(16)

    /**
     * 纯内存极速获取封面位图（O(1) 复杂度，零 IO 与零阻塞）：
     * 供微件 provideContent 组合函数调用，绝不在 UI 组合树执行期间触发数据库或磁盘操作
     */
    fun getCoverFromCache(songId: String, uriString: String): Bitmap? {
        val cacheKey = buildCacheKey(songId, uriString)
        if (cacheKey.isEmpty()) return null
        return coverBitmapCache.get(cacheKey)
    }

    /**
     * 加载微件优化封面位图（挂起异步，执行于 Dispatchers.IO）
     */
    suspend fun loadCover(
        context: Context,
        uriString: String,
        songId: String,
        targetDp: Int = 120
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(songId, uriString)
        if (cacheKey.isNotEmpty()) {
            coverBitmapCache.get(cacheKey)?.let { cached ->
                LogManager.d("$TAG: Cover cache hit for key=$cacheKey")
                return@withContext cached
            }
        }

        val density = context.resources.displayMetrics.density
        val targetPx = (targetDp * density).toInt().coerceIn(120, 360)

        // 阶段一：本地音频文件 ID3 伴生嵌入封面提取优先
        val localCover = loadFromLocalId3Sync(context, songId, targetDp)
        if (localCover != null) {
            if (cacheKey.isNotEmpty()) coverBitmapCache.put(cacheKey, localCover)
            return@withContext localCover
        }

        // 阶段二：通过 Coil 请求网络/磁盘缓存
        if (uriString.isBlank()) {
            LogManager.d("$TAG: Empty artwork URI and no local ID3 art found for songId=$songId")
            return@withContext null
        }

        val coilCover = loadFromCoil(context, uriString, targetPx)
        if (coilCover != null) {
            if (cacheKey.isNotEmpty()) coverBitmapCache.put(cacheKey, coilCover)
            return@withContext coilCover
        }

        LogManager.w("$TAG: All cover loading strategies exhausted for songId=$songId, uri=$uriString")
        null
    }

    private fun buildCacheKey(songId: String, uriString: String): String {
        return when {
            songId.isNotEmpty() && uriString.isNotEmpty() -> "${songId}_${uriString.hashCode()}"
            songId.isNotEmpty() -> songId
            uriString.isNotEmpty() -> uriString.hashCode().toString()
            else -> ""
        }
    }

    private fun loadFromLocalId3Sync(context: Context, songId: String, targetDp: Int): Bitmap? {
        if (songId.isEmpty()) return null
        return try {
            val db = AppDatabase.getDatabase(context)
            val song = db.musicDao().getSongByIdSync(songId)
            val localUri = song?.localUri?.let { Uri.parse(it) } ?: return null

            val density = context.resources.displayMetrics.density
            val targetPx = (targetDp * density).toInt().coerceIn(120, 360)

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, localUri)
                val picture = retriever.embeddedPicture ?: return null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
                    sample *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeByteArray(picture, 0, picture.size, decodeOpts)
                if (bmp != null) {
                    LogManager.i("$TAG: Extracted local ID3 cover for $songId (${bmp.width}x${bmp.height}, ${bmp.byteCount} bytes)")
                    bmp
                } else null
            } catch (e: Exception) {
                LogManager.w("$TAG: Failed extracting ID3 art: ${e.message}")
                null
            } finally {
                try { retriever.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            LogManager.w("$TAG: Database or file lookup error for local ID3: ${e.message}")
            null
        }
    }

    private suspend fun loadFromCoil(context: Context, uriString: String, targetPx: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val loader = SingletonImageLoader.get(context)

            val requestBuilder = ImageRequest.Builder(context)
                .data(uri)
                .size(targetPx, targetPx)
                .allowHardware(false)

            val stableKey = CoverArtKeyUtils.buildStableKey(uri)
            if (stableKey != null) {
                requestBuilder.diskCacheKey(stableKey)
                requestBuilder.diskCachePolicy(coil3.request.CachePolicy.ENABLED)
            }

            val result = loader.execute(requestBuilder.build())
            if (result is SuccessResult) {
                val rawBitmap = (result.image as? BitmapImage)?.bitmap
                val finalBmp = when {
                    rawBitmap == null -> null
                    rawBitmap.config == Bitmap.Config.RGB_565 -> rawBitmap
                    else -> rawBitmap.copy(Bitmap.Config.RGB_565, false)
                }
                if (finalBmp != null) {
                    LogManager.i("$TAG: Loaded Coil cover for $uriString (${finalBmp.width}x${finalBmp.height}, ${finalBmp.byteCount} bytes)")
                }
                finalBmp
            } else {
                LogManager.w("$TAG: Coil image load returned non-success result: $result")
                null
            }
        } catch (e: Exception) {
            LogManager.w("$TAG: Coil cover load failed: ${e.message}")
            null
        }
    }
}
