package de.lwp2070809.speculonic.domain.repository

import android.net.Uri
import androidx.core.net.toUri
import de.lwp2070809.speculonic.util.AppConstants


class UrlBuilder(
    private val baseUrl: String,
    private val authManager: AuthManager
) {
    private fun buildBaseUri(endpoint: String, isForImageContent: Boolean = false, includeAuthParams: Boolean = true): Uri.Builder {
        val cleanBaseUrl = baseUrl.removeSuffix("/")
        val builder = "$cleanBaseUrl/rest/$endpoint".toUri().buildUpon()
        if (includeAuthParams) {
            val (u, t, s) = authManager.getAuthParams(isForImageContent = isForImageContent)
            builder.appendQueryParameter("u", u)
                .appendQueryParameter("t", t)
                .appendQueryParameter("s", s)
                .appendQueryParameter("v", AppConstants.SUBSONIC_API_VERSION)
                .appendQueryParameter("c", AppConstants.SUBSONIC_CLIENT_ID)
                .appendQueryParameter("f", "json")
        }
        return builder
    }

    private fun buildMediaUrl(endpoint: String, id: String): String {
        return buildBaseUri(endpoint, includeAuthParams = false)
            .appendQueryParameter("id", id)
            .appendQueryParameter("format", "raw")
            .build().toString()
    }

    fun buildStreamUrl(id: String): String {
        return buildMediaUrl("stream", id)
    }

    fun buildDownloadUrl(id: String): String {
        return buildMediaUrl("download", id)
    }

    fun buildCoverArtUrl(id: String): String {
        return buildBaseUri("getCoverArt", isForImageContent = true)
            .appendQueryParameter("id", id)
            .build().toString()
    }
}

