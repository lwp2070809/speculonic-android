package de.lwp2070809.speculonic.network.interceptor

import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.network.BlockReason
import de.lwp2070809.speculonic.network.RequestGateResult
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

internal class ServerUnavailableException(message: String) : IOException(message)

class SonicLogInterceptor(
    private val allowInsecureProvider: () -> Boolean = { DynamicSslTrustManager.allowInsecureConnections },
    private val trackReachability: Boolean = true
) : Interceptor {
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safeUrl = maskSensitiveUrl(request.url.toString())
        validateRequestPreconditions(request, safeUrl)

        val permit = acquirePermitIfTracked(safeUrl)
        val startTime = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            recordResponseOutcome(request, response, permit, startTime)
            return response
        } catch (e: Exception) {
            handleRequestException(request, permit, startTime, e)
            throw e
        } finally {
            if (permit != null && permit.isProbe) {
                ServerReachableManager.releaseProbeIfNeeded(permit)
            }
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
                throw ServerUnavailableException("Offline: Manual offline mode is enabled")
            }
            !ServerReachableManager.isPhysicallyConnected -> {
                LogManager.w(LogTag.NETWORK, "OfflineInterceptor: disconnected: $safeUrl")
                throw ServerUnavailableException("Offline: No active network connection available")
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

    private fun acquirePermitIfTracked(safeUrl: String): RequestGateResult.Allowed? {
        if (!trackReachability) return null
        return when (val gate = ServerReachableManager.acquireRequestPermit()) {
            is RequestGateResult.Allowed -> {
                if (gate.isProbe) {
                    LogManager.i(LogTag.NETWORK, "OfflineInterceptor: probe: $safeUrl")
                }
                gate
            }
            is RequestGateResult.Blocked -> {
                val message = when (gate.reason) {
                    BlockReason.COOLDOWN -> {
                        LogManager.w(LogTag.NETWORK, "OfflineInterceptor: unreachable: $safeUrl")
                        "Offline: Server is unreachable due to network blocking or previous failures"
                    }
                    BlockReason.PROBE_IN_PROGRESS -> {
                        LogManager.w(LogTag.NETWORK, "OfflineInterceptor: probing in progress, blocking: $safeUrl")
                        "Offline: Server is unreachable and another probe is in progress"
                    }
                }
                throw ServerUnavailableException(message)
            }
        }
    }

    private fun recordResponseOutcome(
        request: Request,
        response: Response,
        permit: RequestGateResult.Allowed?,
        startTime: Long
    ) {
        val path = request.url.encodedPath
        val costMs = System.currentTimeMillis() - startTime
        if (!response.isSuccessful) {
            if (permit != null && permit.isProbe) {
                ServerReachableManager.reportFailure(permit)
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
        if (permit != null) {
            ServerReachableManager.reportSuccess(permit)
        }
    }

    private fun handleRequestException(
        request: Request,
        permit: RequestGateResult.Allowed?,
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

        if (permit != null && (permit.isProbe || isNetworkFailure)) {
            LogManager.w(
                LogTag.NETWORK,
                "Network Connection Error (${costMs}ms): ${e.message} for host $requestHost. " +
                    "Recording failure (probe=${permit.isProbe})."
            )
            ServerReachableManager.reportFailure(permit)
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
