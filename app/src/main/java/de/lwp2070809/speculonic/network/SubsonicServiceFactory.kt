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
    fun clearCache() {
        cachedService = null
        cachedBaseUrl = null
    }

    @Synchronized
    fun getOrCreateService(baseUrl: String): SubsonicService {
        val sanitizedUrl = sanitizeBaseUrl(baseUrl)

        if (cachedService != null && cachedBaseUrl == sanitizedUrl) {
            return cachedService!!
        }

        try {
            val service = buildService(sanitizedUrl, okHttpClient, json)
            cachedService = service
            cachedBaseUrl = sanitizedUrl
            return service
        } catch (e: Exception) {
            LogManager.w(
                "Failed to create SubsonicService with URL: ${LogManager.redactUrl(sanitizedUrl)}. " +
                    "Falling back to http://unconfigured.local/",
                e
            )
        }

        val fallbackUrl = FALLBACK_URL
        if (cachedService != null && cachedBaseUrl == fallbackUrl) {
            return cachedService!!
        }

        val service = buildService(fallbackUrl, okHttpClient, json)
        cachedService = service
        cachedBaseUrl = fallbackUrl
        return service
    }

    companion object {
        private const val FALLBACK_URL = "http://unconfigured.local/"

        internal fun sanitizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return when {
                trimmed.isBlank() -> FALLBACK_URL
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                    if (trimmed.endsWith("/")) trimmed else "$trimmed/"
                }
                else -> "http://invalid-url-no-protocol/"
            }
        }

        /**
         * 直接构建不写入缓存的 SubsonicService 实例，供测试连接等隔离场景使用。
         */
        fun createUncachedService(
            baseUrl: String,
            okHttpClient: OkHttpClient,
            json: Json
        ): SubsonicService = buildService(sanitizeBaseUrl(baseUrl), okHttpClient, json)

        private fun buildService(
            sanitizedUrl: String,
            okHttpClient: OkHttpClient,
            json: Json
        ): SubsonicService {
            val contentType = "application/json".toMediaType()
            return Retrofit.Builder()
                .baseUrl(sanitizedUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(SubsonicService::class.java)
        }
    }
}
