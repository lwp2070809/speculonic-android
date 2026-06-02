package de.lwp2070809.speculonic.util

import android.net.Uri
import androidx.core.net.toUri
import coil3.key.Keyer
import coil3.request.Options


object CoverArtKeyUtils {
    
    fun normalizeBaseUrl(url: String): String {
        return url.trim()
            .lowercase()
            .replace(Regex("/+$"), "")
    }
}


class SubsonicCoverArtKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        val uriString = data.toString()
        
        if (!uriString.contains("/rest/getCoverArt")) return null

        return try {
            val id = data.getQueryParameter("id")

            if (id != null) {
                
                val baseUrl = uriString.substringBefore("/rest/getCoverArt")
                val normalizedBaseUrl = CoverArtKeyUtils.normalizeBaseUrl(baseUrl)
                "cover_${normalizedBaseUrl.hashCode()}_$id"
            } else {
                null 
            }
        } catch (e: Exception) {
            null 
        }
    }
}

class SubsonicCoverArtStringKeyer : Keyer<String> {
    private val uriKeyer = SubsonicCoverArtKeyer()
    override fun key(data: String, options: Options): String? {
        return try {
            uriKeyer.key(data.toUri(), options)
        } catch (e: Exception) {
            null
        }
    }
}
