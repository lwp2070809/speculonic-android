package de.lwp2070809.speculonic.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

class CacheOverrideInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = chain.request().url.encodedPath
        if (path.contains("getCoverArt") || path.contains("getAvatar")) {
            val cacheControl = response.header("Cache-Control")
            if (cacheControl == null ||
                cacheControl.contains("no-cache") ||
                cacheControl.contains("no-store") ||
                cacheControl.contains("max-age=0")
            ) {
                return response.newBuilder()
                    .header("Cache-Control", "public, max-age=31536000")
                    .removeHeader("Pragma")
                    .removeHeader("Expires")
                    .build()
            }
        }
        return response
    }
}
