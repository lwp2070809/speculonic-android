package de.lwp2070809.speculonic.network.interceptor

import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class SonicLogInterceptor(
    private val allowInsecureProvider: () -> Boolean = { DynamicSslTrustManager.allowInsecureConnections },
    private val trackReachability: Boolean = true
) : Interceptor {
    companion object {
        private const val PROBE_COOLDOWN_MS = 30_000L

        /**
         * 敏感参数脱敏正则：利用正向后行断言 (?<=[?&]) 精确匹配作为独立参数的 [utps]，
         * 避免误伤如 offset, format, songCount, albumCount 等包含上述字符作为子串的业务参数。
         */
        val SENSITIVE_PARAM_REGEX = Regex("(?<=[?&])([utps])=[^&]+")

        fun maskSensitiveUrl(url: String): String {
            return url.replace(SENSITIVE_PARAM_REGEX, "$1=***")
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safeUrl = maskSensitiveUrl(request.url.toString())
        validateRequestPreconditions(request, safeUrl)

        val probeToken = acquireProbeTokenIfNeeded(safeUrl)
        val startTime = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            recordResponseOutcome(request, response, probeToken, startTime)
            return response
        } catch (e: Exception) {
            handleRequestException(request, probeToken, startTime, e)
            throw e
        } finally {
            probeToken?.let { ServerReachableManager.releaseProbe(it) }
        }
    }

    private fun validateRequestPreconditions(request: Request, safeUrl: String) {
        val host = request.url.host
        val blockReason = when {
            host == "unconfigured.local" || host == "invalid-url-no-protocol" -> {
                LogManager.i(LogTag.NETWORK, "OfflineInterceptor: App is not configured, aborting dummy request: $safeUrl")
                "Unconfigured: Server is not configured yet"
            }
            ServerReachableManager.isManualOffline -> {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: manual offline: $safeUrl")
                "Offline: Manual offline mode is enabled"
            }
            !ServerReachableManager.isPhysicallyConnected -> {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: disconnected: $safeUrl")
                "Offline: No active network connection available"
            }
            !request.url.isHttps && !allowInsecureProvider() -> {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: Insecure connection blocked: $safeUrl")
                "Insecure connection blocked: HTTP is not allowed by default. " +
                    "Please use HTTPS or enable 'Allow insecure connections' in settings."
            }
            else -> null
        }
        if (blockReason != null) {
            throw IOException(blockReason)
        }
    }

    private fun acquireProbeTokenIfNeeded(safeUrl: String): Long? {
        if (!trackReachability || ServerReachableManager.isServerReachable) return null

        val timeSinceLastFailure = System.currentTimeMillis() - ServerReachableManager.lastFailureTimestamp
        if (timeSinceLastFailure <= PROBE_COOLDOWN_MS) {
            LogManager.w(LogTag.NETWORK, "OfflineInterceptor: unreachable: $safeUrl")
            throw IOException("Offline: Server is unreachable due to network blocking or previous failures")
        }
        val acquiredToken = ServerReachableManager.tryAcquireProbe()
            ?: run {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: probing in progress, blocking: $safeUrl")
                throw IOException("Offline: Server is unreachable and another probe is in progress")
            }
        LogManager.i(LogTag.NETWORK, "OfflineInterceptor: probe: $safeUrl")
        return acquiredToken
    }

    private fun recordResponseOutcome(
        request: Request,
        response: Response,
        probeToken: Long?,
        startTime: Long
    ) {
        val path = request.url.encodedPath
        val costMs = System.currentTimeMillis() - startTime
        if (!response.isSuccessful) {
            if (trackReachability && probeToken != null) {
                ServerReachableManager.handleFailure()
            }
            LogManager.w(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
            return
        }

        val isCoverArtRequest = path.contains("getCoverArt", ignoreCase = true)
        val isMediaDataRequest = path.contains("stream", ignoreCase = true) ||
            path.contains("download", ignoreCase = true)
        if (!isCoverArtRequest && !isMediaDataRequest) {
            LogManager.i(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
        } else if (BuildConfig.DEBUG) {
            LogManager.d(LogTag.NETWORK, "HTTP ${request.method} $path -> ${response.code} (${costMs}ms)")
        }
        if (trackReachability) {
            ServerReachableManager.handleSuccess()
        }
    }

    private fun handleRequestException(
        request: Request,
        probeToken: Long?,
        startTime: Long,
        e: Exception
    ) {
        if (e.message?.startsWith("Unconfigured") == true) return

        val costMs = System.currentTimeMillis() - startTime
        val requestHost = request.url.host
        val path = request.url.encodedPath
        val isNetworkFailure = e is UnknownHostException ||
            e is ConnectException ||
            e is SocketTimeoutException ||
            e is SocketException ||
            e is SSLException

        if (trackReachability && (probeToken != null || isNetworkFailure)) {
            LogManager.w(
                LogTag.NETWORK,
                "Network Connection Error (${costMs}ms): ${e.message} for host $requestHost. " +
                    "Recording failure (probe=${probeToken != null})."
            )
            ServerReachableManager.handleFailure()
        }

        val errorMsg = buildDiagnosticMessage(requestHost, e)
        LogManager.e(
            LogTag.NETWORK,
            "Network connection diagnosis: $errorMsg (Host: $requestHost, Path: $path, Cost: ${costMs}ms)",
            e
        )
    }

    private fun buildDiagnosticMessage(requestHost: String, e: Exception): String {
        return when (e) {
            is CertificateException,
            is SSLHandshakeException,
            is SSLException -> {
                "SSL handshake failed! Please check if your self-hosted server certificate is valid or expired. " +
                    "Current allow insecure connections mode is: ${allowInsecureProvider()}"
            }
            is UnknownHostException -> {
                "DNS resolution failed! Host not found: $requestHost. " +
                    "Please check your network connection or server hostname configuration."
            }
            is ConnectException,
            is SocketTimeoutException -> {
                "Network connection timeout or refused! Server $requestHost might be offline or blocked by a firewall."
            }
            else -> "Network request failed: ${e.message}"
        }
    }
}
