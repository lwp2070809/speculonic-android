package de.lwp2070809.speculonic.di

import de.lwp2070809.speculonic.R

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.Inet4Address
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val DEFAULT_TIMEOUT = 30L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    
    object ServerReachableManager {
        @Volatile
        var isServerReachable: Boolean = true
            private set

        private val failureCount = java.util.concurrent.atomic.AtomicInteger(0)

        @Synchronized
        fun handleSuccess() {
            failureCount.set(0)
            if (!isServerReachable) {
                isServerReachable = true
                LogManager.i("ServerReachableManager: 请求成功。服务器已标记为可达 (REACHABLE)。")
            }
        }

        @Synchronized
        fun handleFailure() {
            val current = failureCount.incrementAndGet()
            if (current >= 3) {
                if (isServerReachable) {
                    isServerReachable = false
                    LogManager.w("ServerReachableManager: 连续 3 次发生网络连接失败。标记服务器为不可达 (UNREACHABLE)。")
                    
                    val app = try { de.lwp2070809.speculonic.SpeculonicApp.instance } catch(e: Exception) { null }
                    val cm = app?.getSystemService(ConnectivityManager::class.java)
                    if (app != null && cm?.activeNetwork != null) {
                        val prefs = de.lwp2070809.speculonic.data.PreferencesManager.getInstance(app)
                        if (prefs.getShowOfflineToastSync()) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    app, 
                                    app.getString(de.lwp2070809.speculonic.R.string.server_offline_toast),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            } else {
                LogManager.d("ServerReachableManager: 网络请求失败，已记录一次失败计数 ($current/3)。")
            }
        }

        
        @Synchronized
        fun reset() {
            failureCount.set(0)
            if (!isServerReachable) {
                isServerReachable = true
                LogManager.i("ServerReachableManager: 网络恢复可用。重置失败计数，并将服务器重新标记为可达 (REACHABLE)。")
            }
        }

        
        @Synchronized
        fun handleNetworkLost() {
            failureCount.set(0)
            if (isServerReachable) {
                isServerReachable = false
                LogManager.w("ServerReachableManager: 网络连接丢失 (onLost)。标记服务器为不可达 (UNREACHABLE)，等待网络恢复。")
            }
        }

        
        fun isOfflineOrUnreachable(): Boolean {
            
            val app = try {
                de.lwp2070809.speculonic.SpeculonicApp.instance
            } catch (e: Exception) {
                null
            }
            if (app != null) {
                val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
                if (cm?.activeNetwork == null) {
                    return true
                }
            }
            
            return !isServerReachable
        }
    }

    private class SonicLogInterceptor : Interceptor {
        private fun isPhysicallyDisconnected(): Boolean {
            val app = try {
                de.lwp2070809.speculonic.SpeculonicApp.instance
            } catch (e: Exception) {
                null
            } ?: return false

            val connectivityManager = app.getSystemService(ConnectivityManager::class.java) ?: return false
            return connectivityManager.activeNetwork == null
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()
            
            val safeUrl = url.replace(Regex("([utps])=[^&]+"), "$1=***")

            
            if (isPhysicallyDisconnected()) {
                LogManager.w("OfflineInterceptor: 设备处于完全断网状态。秒拦截请求，不发送网络包。URL: $safeUrl")
                throw java.io.IOException("Offline: No active network connection available")
            }

            
            if (!ServerReachableManager.isServerReachable) {
                LogManager.w("OfflineInterceptor: 当前服务器标记为不可达 (UNREACHABLE)。秒拦截请求，不发送网络包。URL: $safeUrl")
                throw java.io.IOException("Offline: Server is unreachable due to network blocking or previous failures")
            }

            if (de.lwp2070809.speculonic.BuildConfig.DEBUG) {
                LogManager.d("Network Request: ${request.method} $safeUrl")
            }

            try {
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    LogManager.w("Network Response Failed: Code ${response.code} for $safeUrl")
                } else {
                    if (de.lwp2070809.speculonic.BuildConfig.DEBUG) {
                        LogManager.d("Network Response: ${response.code} for $safeUrl")
                    }
                    
                    ServerReachableManager.handleSuccess()
                }
                return response
            } catch (e: Exception) {
                val host = request.url.host

                
                if (e is java.net.UnknownHostException || 
                    e is java.net.ConnectException || 
                    e is java.net.SocketTimeoutException) {
                    LogManager.w("Network Connection Error: ${e.message} for host $host. Recording failure.")
                    ServerReachableManager.handleFailure()
                }

                val errorMsg = when (e) {
                    is java.security.cert.CertificateException,
                    is javax.net.ssl.SSLHandshakeException,
                    is javax.net.ssl.SSLException -> {
                        "SSL证书握手失败！请检查您的自托管服务器证书是否有效或过期。当前信任所有证书(trustAll)模式为: ${DynamicSslTrustManager.trustAll}"
                    }
                    is java.net.UnknownHostException -> {
                        "DNS解析失败！找不到主机: $host。请检查您的网络连接或服务器主机名配置是否正确。"
                    }
                    is java.net.ConnectException,
                    is java.net.SocketTimeoutException -> {
                        "网络连接超时或被拒绝！服务器 $host 可能已下线，或者被防火墙拦截。"
                    }
                    else -> {
                        "网络请求失败: ${e.message}"
                    }
                }
                LogManager.e("网络连接诊断: $errorMsg (主机: $host)", e)
                throw e
            }
        }
    }

    private class CacheOverrideInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val path = chain.request().url.encodedPath
            if (path.contains("getCoverArt") || path.contains("getAvatar")) {
                val cacheControl = response.header("Cache-Control")
                if (cacheControl == null || cacheControl.contains("no-cache") || cacheControl.contains("no-store") || cacheControl.contains("max-age=0")) {
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

    
    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    object DynamicSslTrustManager : X509TrustManager {
        
        @Volatile
        var trustAll: Boolean = false

        
        private val systemTrustManager: X509TrustManager by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (trustAll) {
                
                return
            }
            
            systemTrustManager.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return if (trustAll) arrayOf() else systemTrustManager.acceptedIssuers
        }
    }

    
    private val ipv4PreferredDns = Dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        
        addresses.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
    }

    
    private fun registerNetworkChangeObserver() {
        val app = try {
            de.lwp2070809.speculonic.SpeculonicApp.instance
        } catch (e: Exception) {
            null
        } ?: return

        val connectivityManager = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var wasInternetAvailable = false
            private var wasVpn = false

            override fun onAvailable(network: android.net.Network) {
                wasInternetAvailable = true 
                ServerReachableManager.reset()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

                val internetRestored = hasInternet && !wasInternetAvailable
                val vpnToggled = isVpn != wasVpn

                if (internetRestored || vpnToggled) {
                    ServerReachableManager.reset()
                    LogManager.i("NetworkModule: Network state changed (Internet Restored: $internetRestored, VPN Toggled: $vpnToggled). Resetting Reachable status.")
                }
                
                wasInternetAvailable = hasInternet
                wasVpn = isVpn
            }

            override fun onLost(network: android.net.Network) {
                wasInternetAvailable = false
                wasVpn = false
                
                ServerReachableManager.handleNetworkLost()
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            LogManager.i("NetworkModule: Successfully registered default network callback for Reachable reset.")
        } catch (e: Exception) {
            LogManager.w("NetworkModule: Failed to register default network callback: ${e.message}")
        }
    }

    private val okHttpClientInstance: OkHttpClient by lazy {
        registerNetworkChangeObserver()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(DynamicSslTrustManager), SecureRandom())

        val defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val dynamicHostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, session ->
            DynamicSslTrustManager.trustAll || defaultHostnameVerifier.verify(hostname, session)
        }

        
        
        
        
        
        
        
        
        
        
        
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(dynamicHostnameVerifier)
        LogManager.i("NetworkModule: 已设置全局 HttpsURLConnection 默认 SSLSocketFactory（DynamicSslTrustManager）")

        OkHttpClient.Builder()
            .addInterceptor(SonicLogInterceptor())
            .addNetworkInterceptor(CacheOverrideInterceptor())
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            
            .sslSocketFactory(sslContext.socketFactory, DynamicSslTrustManager)
            
            .hostnameVerifier(dynamicHostnameVerifier)
            
            .dns(ipv4PreferredDns)
            .build()
    }

    
    fun rebuildClientIfNeeded(trustAll: Boolean) {
        if (DynamicSslTrustManager.trustAll != trustAll) {
            DynamicSslTrustManager.trustAll = trustAll
            LogManager.i("NetworkModule: SSL 信任模式已切换，trustAll=$trustAll（无需重建 OkHttpClient）")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return okHttpClientInstance
    }

    
    private var cachedService: SubsonicService? = null
    private var cachedBaseUrl: String? = null

    fun provideSubsonicService(baseUrl: String): SubsonicService {
        synchronized(this) {
            val trimmed = baseUrl.trim()
            val sanitizedUrl = when {
                trimmed.isBlank() -> "http://localhost/"
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                    if (trimmed.endsWith("/")) trimmed else "$trimmed/"
                }
                else -> {
                    
                    "http://invalid-url-no-protocol/"
                }
            }

            
            if (cachedService != null && cachedBaseUrl == sanitizedUrl) {
                return cachedService!!
            }

            
            try {
                val contentType = "application/json".toMediaType()
                val service = Retrofit.Builder()
                    .baseUrl(sanitizedUrl)
                    .client(okHttpClientInstance)
                    .addConverterFactory(json.asConverterFactory(contentType))
                    .build()
                    .create(SubsonicService::class.java)
                cachedService = service
                cachedBaseUrl = sanitizedUrl
                return service
            } catch (e: Exception) {
                LogManager.w("Failed to create SubsonicService with URL: $sanitizedUrl. Falling back to http://localhost/")
            }

            
            val fallbackUrl = "http://localhost/"
            if (cachedService != null && cachedBaseUrl == fallbackUrl) {
                return cachedService!!
            }

            val contentType = "application/json".toMediaType()
            val service = Retrofit.Builder()
                .baseUrl(fallbackUrl)
                .client(okHttpClientInstance)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()
                .create(SubsonicService::class.java)
            cachedService = service
            cachedBaseUrl = fallbackUrl
            return service
        }
    }
}
