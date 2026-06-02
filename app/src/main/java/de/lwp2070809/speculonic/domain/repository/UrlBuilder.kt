package de.lwp2070809.speculonic.domain.repository

import android.net.Uri
import androidx.core.net.toUri


class UrlBuilder(
    private val baseUrl: String,
    private val authManager: AuthManager
) {
    private fun buildBaseUri(endpoint: String, isForImageContent: Boolean = false): Uri.Builder {
        val (u, t, s) = authManager.getAuthParams(isForImageContent = isForImageContent)
        val cleanBaseUrl = baseUrl.removeSuffix("/")
        return "$cleanBaseUrl/rest/$endpoint".toUri().buildUpon()
            .appendQueryParameter("u", u)
            .appendQueryParameter("t", t)
            .appendQueryParameter("s", s)
            .appendQueryParameter("v", "1.16.1")
            .appendQueryParameter("c", "Speculonic")
            .appendQueryParameter("f", "json")
    }

    fun buildStreamUrl(id: String): String {
        return buildBaseUri("stream")
            .appendQueryParameter("id", id)
            .appendQueryParameter("format", "raw")
            .build().toString()
    }

    fun buildDownloadUrl(id: String): String {
        return buildBaseUri("download")
            .appendQueryParameter("id", id)
            .appendQueryParameter("format", "raw")
            .build().toString()
    }

    fun buildCoverArtUrl(id: String): String {
        return buildBaseUri("getCoverArt", isForImageContent = true)
            .appendQueryParameter("id", id)
            .build().toString()
    }
}

