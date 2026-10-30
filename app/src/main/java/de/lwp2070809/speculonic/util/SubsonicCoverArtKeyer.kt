package de.lwp2070809.speculonic.util

import android.net.Uri
import androidx.core.net.toUri
import coil3.key.Keyer
import coil3.request.Options

object CoverArtKeyUtils {
    fun normalizeBaseUrl(url: String): String = url.trim().lowercase().trimEnd('/')

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
            null
        }
    }

    fun buildStableKey(uriString: String): String? {
        if (!uriString.contains("/rest/getCoverArt")) return null
        return try {
            buildStableKey(uriString.toUri())
        } catch (e: Exception) {
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
