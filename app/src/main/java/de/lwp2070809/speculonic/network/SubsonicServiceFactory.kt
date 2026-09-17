package de.lwp2070809.speculonic.network

import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SubsonicServiceFactory(
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
) {
    private var cachedService: SubsonicService? = null
    private var cachedBaseUrl: String? = null

    @Synchronized
    fun getOrCreateService(baseUrl: String): SubsonicService {
        val trimmed = baseUrl.trim()
        val sanitizedUrl = when {
            trimmed.isBlank() -> "http://unconfigured.local/"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            }
            else -> "http://invalid-url-no-protocol/"
        }

        if (cachedService != null && cachedBaseUrl == sanitizedUrl) {
            return cachedService!!
        }

        try {
            val contentType = "application/json".toMediaType()
            val service = Retrofit.Builder()
                .baseUrl(sanitizedUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(SubsonicService::class.java)
            cachedService = service
            cachedBaseUrl = sanitizedUrl
            return service
        } catch (e: Exception) {
            LogManager.w("Failed to create SubsonicService with URL: $sanitizedUrl. Falling back to http://unconfigured.local/")
        }

        val fallbackUrl = "http://unconfigured.local/"
        if (cachedService != null && cachedBaseUrl == fallbackUrl) {
            return cachedService!!
        }

        val contentType = "application/json".toMediaType()
        val service = Retrofit.Builder()
            .baseUrl(fallbackUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(SubsonicService::class.java)
        cachedService = service
        cachedBaseUrl = fallbackUrl
        return service
    }
}
