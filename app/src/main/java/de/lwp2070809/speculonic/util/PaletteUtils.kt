package de.lwp2070809.speculonic.util

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PaletteUtils {
    
    private val colorCache = LruCache<String, Color>(100)
    
    
    private val lockPool = Array(16) { Mutex() }

    
    suspend fun getSeedColorAsync(
        context: Context,
        uri: String,
        isDark: Boolean,
        imageLoader: ImageLoader,
        fallbackBackgroundColor: Int
    ): Color? = withContext(Dispatchers.IO) {
        
        val uriObj = uri.toUri()
        val id = uriObj.getQueryParameter("id")
        val stableKey = if (id != null) {
            val baseUrl = uri.substringBefore("/rest/getCoverArt")
            val normalizedBaseUrl = CoverArtKeyUtils.normalizeBaseUrl(baseUrl)
            "cover_${normalizedBaseUrl.hashCode()}_$id"
        } else null

        val cacheKey = "${stableKey ?: uri}_${isDark}_$fallbackBackgroundColor"
        
        
        colorCache.get(cacheKey)?.let {
            return@withContext it
        }

        
        val lockIndex = kotlin.math.abs(cacheKey.hashCode()) % lockPool.size
        lockPool[lockIndex].withLock {
            
            colorCache.get(cacheKey)?.let {
                return@withContext it
            }

            
            try {
                val requestBuilder = ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false) 
                    .size(128, 128) 
            
            
            if (stableKey != null) {
                requestBuilder.diskCacheKey(stableKey)
                requestBuilder.memoryCacheKey("${stableKey}_128")
            }
            
            val request = requestBuilder.build()
            
            val result = imageLoader.execute(request)
            if (result is SuccessResult) {
                val bitmap = result.image.toBitmap()
                val palette = Palette.from(bitmap).generate()
                val color = extractSeedColor(palette, isDark, fallbackBackgroundColor)
                
                if (color != null) {
                    colorCache.put(cacheKey, color)
                }
                color
            } else {
                LogManager.e("PaletteUtils: ImageLoader FAILURE for $uri, result=${result.javaClass.simpleName}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("PaletteUtils: 封面异步取色失败 - $uri", e)
            null
        }
        } 
    }

    
    fun extractSeedColor(palette: Palette, isDark: Boolean, fallbackBackgroundColor: Int): Color? {
        val backgroundColor = fallbackBackgroundColor
        
        
        val swatches = if (isDark) {
            listOfNotNull(
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.mutedSwatch,
                palette.lightMutedSwatch,
                palette.dominantSwatch
            )
        } else {
            listOfNotNull(
                palette.vibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.darkMutedSwatch,
                palette.dominantSwatch
            )
        }
        
        
        
        val minContrast = 3.0
        
        for (swatch in swatches) {
            val contrast = ColorUtils.calculateContrast(swatch.rgb, backgroundColor)
            if (contrast >= minContrast) {
                return Color(swatch.rgb)
            }
        }
        
        
        for (swatch in palette.swatches) {
            val contrast = ColorUtils.calculateContrast(swatch.rgb, backgroundColor)
            if (contrast >= minContrast) {
                return Color(swatch.rgb)
            }
        }
        
        return null
    }
}

