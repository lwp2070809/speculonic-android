package de.lwp2070809.speculonic.network.interceptor

import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.network.NetworkEvent
import de.lwp2070809.speculonic.network.ServerReachableManager
import de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager
import de.lwp2070809.speculonic.util.LogManager
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
    private fun isPhysicallyDisconnected(): Boolean {
        return !ServerReachableManager.isPhysicallyConnected
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val safeUrl = url.replace(Regex("([utps])=[^&]+"), "$1=***")

        val host = request.url.host
        if (host == "unconfigured.local" || host == "invalid-url-no-protocol") {
            LogManager.i("OfflineInterceptor: App is not configured, aborting dummy request: $safeUrl")
            throw IOException("Unconfigured: Server is not configured yet")
        }

        if (ServerReachableManager.isManualOffline) {
            LogManager.w("OfflineInterceptor: manual offline: $safeUrl")
            throw IOException("Offline: Manual offline mode is enabled")
        }

        if (isPhysicallyDisconnected()) {
            LogManager.w("OfflineInterceptor: disconnected: $safeUrl")
            throw IOException("Offline: No active network connection available")
        }

        if (!ServerReachableManager.isServerReachable) {
            val timeSinceLastFailure = System.currentTimeMillis() - ServerReachableManager.lastFailureTimestamp
            if (timeSinceLastFailure > 30000L) {
                LogManager.i("OfflineInterceptor: probe: $safeUrl")
            } else {
                LogManager.w("OfflineInterceptor: unreachable: $safeUrl")
                throw IOException("Offline: Server is unreachable due to network blocking or previous failures")
            }
        }

        val isHttps = request.url.isHttps
        if (!isHttps && !DynamicSslTrustManager.allowInsecureConnections) {
            LogManager.w("OfflineInterceptor: Insecure connection blocked: $safeUrl")
            throw IOException("Insecure connection blocked: HTTP is not allowed by default. Please use HTTPS or enable 'Allow insecure connections' in settings.")
        }

        if (BuildConfig.DEBUG) {
            LogManager.d("Network Request: ${request.method} $safeUrl")
        }

        try {
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                LogManager.w("Network Response Failed: Code ${response.code} for $safeUrl")
            } else {
                if (BuildConfig.DEBUG) {
                    LogManager.d("Network Response: ${response.code} for $safeUrl")
                }
                ServerReachableManager.handleSuccess()
            }
            return response
        } catch (e: Exception) {
            val requestHost = request.url.host
            if (e.message?.startsWith("Unconfigured") == true) {
                throw e
            }

            if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException) {
                LogManager.w("Network Connection Error: ${e.message} for host $requestHost. Recording failure.")
                ServerReachableManager.handleFailure()
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
            LogManager.e("Network connection diagnosis: $errorMsg (Host: $requestHost)", e)
            throw e
        }
    }
}
