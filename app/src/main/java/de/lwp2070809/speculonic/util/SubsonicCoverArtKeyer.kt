package de.lwp2070809.speculonic.util

import android.net.Uri
import androidx.core.net.toUri
import coil3.key.Keyer
import coil3.request.Options

object CoverArtKeyUtils {
    private val BT_SYNC_TS_REGEX = Regex("[?&]bt_sync_ts=[^&]*")

    fun normalizeBaseUrl(url: String): String = url.trim().lowercase().trimEnd('/')

    fun stripVolatileParams(uriString: String): String {
        val stripped = uriString.replace(BT_SYNC_TS_REGEX, "")
        return if (!stripped.contains('?') && stripped.contains('&')) {
            stripped.replaceFirst('&', '?')
        } else {
            stripped
        }
    }

    fun buildStableKey(baseUrl: String, id: String): String {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        return "cover_${normalizedBaseUrl.hashCode()}_$id"
    }

    fun buildStableKey(uri: Uri): String? {
        val uriString = uri.toString()
        if (!uriString.contains("/rest/getCoverArt")) return null
        return try {
            val id = uri.getQueryParameter("id") ?: return null
            val baseUrl = uriString.substringBefore("/rest/getCoverArt")
            buildStableKey(baseUrl, id)
        } catch (e: Exception) {
            LogManager.w("CoverArtKeyUtils: Failed to parse URI key", e)
            null
        }
    }

    fun buildStableKey(uriString: String): String? {
        if (!uriString.contains("/rest/getCoverArt")) return null
        return try {
            buildStableKey(uriString.toUri())
        } catch (e: Exception) {
            LogManager.w("CoverArtKeyUtils: Failed to parse URI string key", e)
            null
        }
    }
}

class SubsonicCoverArtKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        return CoverArtKeyUtils.buildStableKey(data)
    }
}

class SubsonicCoverArtStringKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String? {
        return CoverArtKeyUtils.buildStableKey(data)
    }
}
