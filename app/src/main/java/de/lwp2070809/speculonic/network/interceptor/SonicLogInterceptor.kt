package de.lwp2070809.speculonic.network.interceptor

import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.network.NetworkEvent
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException

class SonicLogInterceptor : Interceptor {
    companion object {
        /**
         * 敏感参数脱敏正则：利用正向后行断言 (?<=[?&]) 精确匹配作为独立参数的 [utps]，
         * 避免误伤如 offset, format, songCount, albumCount 等包含上述字符作为子串的业务参数。
         */
        val SENSITIVE_PARAM_REGEX = Regex("(?<=[?&])([utps])=[^&]+")

        fun maskSensitiveUrl(url: String): String {
            return url.replace(SENSITIVE_PARAM_REGEX, "$1=***")
        }
    }

    private fun isPhysicallyDisconnected(): Boolean {
        return !ServerReachableManager.isPhysicallyConnected
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val safeUrl = maskSensitiveUrl(url)
        val path = request.url.encodedPath
        val isCoverArtRequest = path.contains("getCoverArt", ignoreCase = true)
        val isMediaDataRequest = path.contains("stream", ignoreCase = true) || path.contains("download", ignoreCase = true)

        val host = request.url.host
        if (host == "unconfigured.local" || host == "invalid-url-no-protocol") {
            LogManager.i(LogTag.NETWORK, "OfflineInterceptor: App is not configured, aborting dummy request: $safeUrl")
            throw IOException("Unconfigured: Server is not configured yet")
        }

        if (ServerReachableManager.isManualOffline) {
            LogManager.w(LogTag.NETWORK, "OfflineInterceptor: manual offline: $safeUrl")
            throw IOException("Offline: Manual offline mode is enabled")
        }

        if (isPhysicallyDisconnected()) {
            LogManager.w(LogTag.NETWORK, "OfflineInterceptor: disconnected: $safeUrl")
            throw IOException("Offline: No active network connection available")
        }

        var isProbeRequest = false
        if (!ServerReachableManager.isServerReachable) {
            val timeSinceLastFailure = System.currentTimeMillis() - ServerReachableManager.lastFailureTimestamp
            if (timeSinceLastFailure > 30000L) {
                if (ServerReachableManager.tryAcquireProbe()) {
                    isProbeRequest = true
                    LogManager.i(LogTag.NETWORK, "OfflineInterceptor: probe: $safeUrl")
                } else {
                    LogManager.w(LogTag.NETWORK, "OfflineInterceptor: probing in progress, blocking: $safeUrl")
                    throw IOException("Offline: Server is unreachable and another probe is in progress")
                }
            } else {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: unreachable: $safeUrl")
                throw IOException("Offline: Server is unreachable due to network blocking or previous failures")
            }
        }

        val isHttps = request.url.isHttps
        if (!isHttps && !DynamicSslTrustManager.allowInsecureConnections) {
            LogManager.w(LogTag.NETWORK, "OfflineInterceptor: Insecure connection blocked: $safeUrl")
            throw IOException("Insecure connection blocked: HTTP is not allowed by default. Please use HTTPS or enable 'Allow insecure connections' in settings.")
        }

        val startTime = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            val costMs = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                if (isProbeRequest) {
                    ServerReachableManager.handleFailure()
                }
                LogManager.w(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
            } else {
                if (!isCoverArtRequest && !isMediaDataRequest) {
                    LogManager.i(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
                } else if (BuildConfig.DEBUG) {
                    LogManager.d(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
                }
                ServerReachableManager.handleSuccess()
            }
            return response
        } catch (e: Exception) {
            val costMs = System.currentTimeMillis() - startTime
            val requestHost = request.url.host
            if (e.message?.startsWith("Unconfigured") == true) {
                if (isProbeRequest) ServerReachableManager.releaseProbe()
                throw e
            }

            val isNetworkFailure = e is UnknownHostException || 
                e is ConnectException || 
                e is SocketTimeoutException || 
                e is java.net.SocketException || 
                e is javax.net.ssl.SSLException

            if (isProbeRequest || isNetworkFailure) {
                LogManager.w(LogTag.NETWORK, "Network Connection Error (${costMs}ms): ${e.message} for host $requestHost. Recording failure (probe=$isProbeRequest).")
                ServerReachableManager.handleFailure()
            } else if (isProbeRequest) {
                ServerReachableManager.releaseProbe()
            }

            val errorMsg = when (e) {
                is CertificateException,
                is SSLHandshakeException,
                is SSLException -> {
                    "SSL handshake failed! Please check if your self-hosted server certificate is valid or expired. Current allow insecure connections mode is: ${DynamicSslTrustManager.allowInsecureConnections}"
                }
                is UnknownHostException -> {
                    "DNS resolution failed! Host not found: $requestHost. Please check your network connection or server hostname configuration."
                }
                is ConnectException,
                is SocketTimeoutException -> {
                    "Network connection timeout or refused! Server $requestHost might be offline or blocked by a firewall."
                }
                else -> {
                    "Network request failed: ${e.message}"
                }
            }
            LogManager.e(LogTag.NETWORK, "Network connection diagnosis: $errorMsg (Host: $requestHost, Path: $path, Cost: ${costMs}ms)", e)
            throw e
        }
    }
}
