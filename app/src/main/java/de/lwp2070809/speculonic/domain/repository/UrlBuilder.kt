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

    private fun buildMediaUrl(
        endpoint: String,
        id: String,
        suffix: String? = null,
        transcodeIncompatible: Boolean = false,
        targetFormat: String = "mp3"
    ): String {
        val builder = buildBaseUri(endpoint, includeAuthParams = false)
            .appendQueryParameter("id", id)

        val isDirectSupported = de.lwp2070809.speculonic.util.MediaFormatUtils.isDirectPlaybackSupported(suffix)

        if (transcodeIncompatible && !isDirectSupported) {
            builder.appendQueryParameter("format", targetFormat)
        } else {
            builder.appendQueryParameter("format", "raw")
        }

        return builder.build().toString()
    }

    fun buildStreamUrl(
        id: String,
        suffix: String? = null,
        transcodeIncompatible: Boolean = false,
        targetFormat: String = "mp3"
    ): String {
        return buildMediaUrl("stream", id, suffix, transcodeIncompatible, targetFormat)
    }

    fun buildDownloadUrl(
        id: String,
        suffix: String? = null,
        transcodeIncompatible: Boolean = false,
        targetFormat: String = "mp3"
    ): String {
        val isDirectSupported = de.lwp2070809.speculonic.util.MediaFormatUtils.isDirectPlaybackSupported(suffix)
        val endpoint = if (transcodeIncompatible && !isDirectSupported) "stream" else "download"
        return buildMediaUrl(endpoint, id, suffix, transcodeIncompatible, targetFormat)
    }

    fun buildCoverArtUrl(id: String): String {
        return buildBaseUri("getCoverArt", isForImageContent = true)
            .appendQueryParameter("id", id)
            .build().toString()
    }
}

