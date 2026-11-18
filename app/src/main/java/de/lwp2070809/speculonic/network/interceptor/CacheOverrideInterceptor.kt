package de.lwp2070809.speculonic.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class CacheOverrideInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = chain.request().url.encodedPath
        if (path.contains("getCoverArt") || path.contains("getAvatar")) {
            val cacheControl = response.header("Cache-Control")
            val shouldOverride = cacheControl == null || NO_CACHE_DIRECTIVES.any { cacheControl.contains(it) }
            if (shouldOverride) {
                return response.newBuilder()
                    .header("Cache-Control", "public, max-age=31536000")
                    .removeHeader("Pragma")
                    .removeHeader("Expires")
                    .build()
            }
        }
        return response
    }

    private companion object {
        val NO_CACHE_DIRECTIVES = listOf("no-cache", "no-store", "max-age=0")
    }
}
