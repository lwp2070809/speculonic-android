package de.lwp2070809.speculonic.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@OptIn(UnstableApi::class)
class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val networkMonitor: NetworkMonitor
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean {
        return true
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.Default) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalArgumentException("Failed to decode bitmap from data"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.IO) {
            try {
                val imageLoader = SingletonImageLoader.get(context)
                val id = uri.getQueryParameter("id")

                val requestBuilder = coil3.request.ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false)
                    .size(512) 

                if (id != null) {
                    
                    val uriString = uri.toString()
                    val baseUrl = uriString.substringBefore("/rest/getCoverArt")
                    val normalizedBaseUrl = CoverArtKeyUtils.normalizeBaseUrl(baseUrl)
                    val size = uri.getQueryParameter("size")
                    val stableKey = "cover_${normalizedBaseUrl.hashCode()}_$id"

                    requestBuilder.diskCacheKey(stableKey)
                    requestBuilder.memoryCacheKey(if (size != null) "${stableKey}_$size" else stableKey)
                    requestBuilder.diskCachePolicy(coil3.request.CachePolicy.ENABLED)

                    val isOnline = networkMonitor.isOnline.first()

                    if (isOnline) {
                        requestBuilder.networkCachePolicy(coil3.request.CachePolicy.ENABLED)
                    } else {
                        requestBuilder.networkCachePolicy(coil3.request.CachePolicy.READ_ONLY)
                    }
                }

                val request = requestBuilder.build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.image as? BitmapImage)?.bitmap
                    if (bitmap != null) {
                        future.set(bitmap)
                    } else {
                        future.setException(IllegalArgumentException("Result is not a Bitmap"))
                    }
                } else {
                    val errorThrowable = (result as? coil3.request.ErrorResult)?.throwable
                    future.setException(errorThrowable ?: IllegalArgumentException("Failed to load image from URI"))
                }
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }
}
