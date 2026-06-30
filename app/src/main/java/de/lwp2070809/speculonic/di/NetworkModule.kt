package de.lwp2070809.speculonic.di

import de.lwp2070809.speculonic.R

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.UpdateManager
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import javax.inject.Qualifier
import javax.inject.Singleton

import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GithubHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamHttpClient

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(SingletonComponent::class)
interface RepositoryEntryPoint {
    fun subsonicRepository(): de.lwp2070809.speculonic.domain.repository.SubsonicRepository
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val DEFAULT_TIMEOUT = 30L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    
    sealed interface NetworkEvent {
        object ServerOffline : NetworkEvent
        object NetworkRestricted : NetworkEvent
    }

    object ServerReachableManager {
        @Volatile
        var isManualOffline: Boolean = false

        @Volatile
        var isServerReachable: Boolean = true
            private set

        @Volatile
        var isPhysicallyConnected: Boolean = true
            internal set

        @Volatile
        var lastFailureTimestamp: Long = 0L
            private set

        @Volatile
        private var lastFailureLogTime: Long = 0L

        private val failureCount = java.util.concurrent.atomic.AtomicInteger(0)

        private val _networkEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 10)
        val networkEventFlow: kotlinx.coroutines.flow.SharedFlow<NetworkEvent> = _networkEventFlow.asSharedFlow()

        fun emitEvent(event: NetworkEvent) {
            _networkEventFlow.tryEmit(event)
        }

        @Synchronized
        fun handleSuccess() {
            failureCount.set(0)
            if (!isServerReachable) {
                isServerReachable = true
                LogManager.i("ServerReachableManager: REACHABLE")
            }
        }

        @Synchronized
        fun handleFailure() {
            val now = System.currentTimeMillis()
            if (now - lastFailureLogTime > 2000L) {
                lastFailureLogTime = now
                val current = failureCount.incrementAndGet()
                if (current >= 3) {
                    if (isServerReachable) {
                        isServerReachable = false
                        lastFailureTimestamp = now
                        LogManager.w("ServerReachableManager: UNREACHABLE")
                        _networkEventFlow.tryEmit(NetworkEvent.ServerOffline)
                    }
                } else {
                    LogManager.d("ServerReachableManager: $current/3")
                }
            } else {
                LogManager.d("ServerReachableManager: debounce")
            }
        }

        @Synchronized
        fun reset() {
            failureCount.set(0)
            if (!isServerReachable) {
                isServerReachable = true
                LogManager.i("ServerReachableManager: reset")
            }
        }

        @Synchronized
        fun handleNetworkLost() {
            failureCount.set(0)
            if (isServerReachable) {
                isServerReachable = false
                LogManager.w("ServerReachableManager: lost")
            }
        }

        fun isOfflineOrUnreachable(): Boolean {
            return !isPhysicallyConnected || !isServerReachable || isManualOffline
        }
    }

    private class SonicLogInterceptor : Interceptor {
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
                throw java.io.IOException("Unconfigured: Server is not configured yet")
            }

            if (ServerReachableManager.isManualOffline) {
                LogManager.w("OfflineInterceptor: manual offline: $safeUrl")
                throw java.io.IOException("Offline: Manual offline mode is enabled")
            }

            if (isPhysicallyDisconnected()) {
                LogManager.w("OfflineInterceptor: disconnected: $safeUrl")
                throw java.io.IOException("Offline: No active network connection available")
            }

            if (!ServerReachableManager.isServerReachable) {
                val timeSinceLastFailure = System.currentTimeMillis() - ServerReachableManager.lastFailureTimestamp
                if (timeSinceLastFailure > 30000L) {
                    LogManager.i("OfflineInterceptor: probe: $safeUrl")
                } else {
                    LogManager.w("OfflineInterceptor: unreachable: $safeUrl")
                    throw java.io.IOException("Offline: Server is unreachable due to network blocking or previous failures")
                }
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
                if (e.message?.startsWith("Unconfigured") == true) {
                    throw e
                }

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
                        "SSL handshake failed! Please check if your self-hosted server certificate is valid or expired. Current trust all certificates (trustAll) mode is: ${DynamicSslTrustManager.trustAll}"
                    }
                    is java.net.UnknownHostException -> {
                        "DNS resolution failed! Host not found: $host. Please check your network connection or server hostname configuration."
                    }
                    is java.net.ConnectException,
                    is java.net.SocketTimeoutException -> {
                        "Network connection timeout or refused! Server $host might be offline or blocked by a firewall."
                    }
                    else -> {
                        "Network request failed: ${e.message}"
                    }
                }
                LogManager.e("Network connection diagnosis: $errorMsg (Host: $host)", e)
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
        ServerReachableManager.isPhysicallyConnected = connectivityManager.activeNetwork != null

        val callback = object : ConnectivityManager.NetworkCallback() {
            private var wasInternetAvailable = false
            private var wasVpn = false

            override fun onAvailable(network: android.net.Network) {
                ServerReachableManager.isPhysicallyConnected = true
                wasInternetAvailable = true 
                ServerReachableManager.reset()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ServerReachableManager.isPhysicallyConnected = hasInternet
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
                ServerReachableManager.isPhysicallyConnected = false
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
        LogManager.i("NetworkModule: Global HttpsURLConnection default SSLSocketFactory (DynamicSslTrustManager) has been set")

        val loggingInterceptor = okhttp3.logging.HttpLoggingInterceptor().apply {
            level = if (de.lwp2070809.speculonic.BuildConfig.DEBUG) {
                okhttp3.logging.HttpLoggingInterceptor.Level.BODY
            } else {
                okhttp3.logging.HttpLoggingInterceptor.Level.NONE
            }
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
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
            LogManager.i("NetworkModule: SSL trust mode switched, trustAll=$trustAll (no need to rebuild OkHttpClient)")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return okHttpClientInstance
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return "Unknown error"
        try {
            val jsonMessageRegex = """"(?:message|value)"\s*:\s*"([^"]+)"""".toRegex()
            val matchJson = jsonMessageRegex.find(body)
            if (matchJson != null) {
                return matchJson.groupValues[1]
            }
            val xmlMessageRegex = """message="([^"]+)"""".toRegex()
            val matchXml = xmlMessageRegex.find(body)
            if (matchXml != null) {
                return matchXml.groupValues[1]
            }
        } catch (e: Exception) {
            // Ignore
        }
        return body.take(200)
    }

    @Provides
    @Singleton
    @StreamHttpClient
    fun provideStreamOkHttpClient(@ApplicationContext context: android.content.Context): OkHttpClient {
        return okHttpClientInstance.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val path = url.encodedPath
            val isMediaRequest = path.contains("rest/stream") || path.contains("rest/download")
            
            val finalRequest = if (isMediaRequest && url.queryParameter("u") == null) {
                val entryPoint = dagger.hilt.EntryPoints.get(context.applicationContext, RepositoryEntryPoint::class.java)
                val authParams = entryPoint.subsonicRepository().getCurrentAuthParams()
                if (authParams != null) {
                    val (u, t, s) = authParams
                    val newUrl = url.newBuilder()
                        .addQueryParameter("u", u)
                        .addQueryParameter("t", t)
                        .addQueryParameter("s", s)
                        .addQueryParameter("v", de.lwp2070809.speculonic.util.AppConstants.SUBSONIC_API_VERSION)
                        .addQueryParameter("c", de.lwp2070809.speculonic.util.AppConstants.SUBSONIC_CLIENT_ID)
                        .build()
                    request.newBuilder().url(newUrl).build()
                } else {
                    request
                }
            } else {
                request
            }

            val response = chain.proceed(finalRequest)

            if (isMediaRequest) {
                val contentType = response.body.contentType()?.toString()?.lowercase() ?: ""
                val isSuccessful = response.isSuccessful
                
                val isExplicitTextOrError = contentType.contains("json") || 
                                            contentType.contains("xml") || 
                                            contentType.contains("text/")
                                            
                val shouldIntercept = !isSuccessful || isExplicitTextOrError
                
                if (shouldIntercept) {
                    val bodyString = try {
                        response.peekBody(10240).string()
                    } catch (e: Exception) {
                        "Failed to read body: ${e.message}"
                    }
                    LogManager.e("Stream/Download request returned non-audio response: code=${response.code}, contentType=$contentType, body=$bodyString")
                    
                    val errorMessage = extractErrorMessage(bodyString)
                    throw java.io.IOException("Subsonic server returned error (code ${response.code}): $errorMessage")
                }
            }
            response
        }.build()
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: android.content.Context
    ): de.lwp2070809.speculonic.util.NetworkMonitor {
        return de.lwp2070809.speculonic.util.ConnectivityManagerNetworkMonitor(context)
    }

    @Provides
    @Singleton
    @GithubHttpClient
    fun provideGithubOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideUpdateManager(
        @ApplicationContext context: android.content.Context,
        preferencesManager: de.lwp2070809.speculonic.data.PreferencesManager,
        @GithubHttpClient okHttpClient: OkHttpClient
    ): UpdateManager {
        return UpdateManager(context, preferencesManager, okHttpClient)
    }

    
    private var cachedService: SubsonicService? = null
    private var cachedBaseUrl: String? = null

    fun provideSubsonicService(baseUrl: String): SubsonicService {
        synchronized(this) {
            val trimmed = baseUrl.trim()
            val sanitizedUrl = when {
                trimmed.isBlank() -> "http://unconfigured.local/"
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
                LogManager.w("Failed to create SubsonicService with URL: $sanitizedUrl. Falling back to http://unconfigured.local/")
            }

            
            val fallbackUrl = "http://unconfigured.local/"
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
