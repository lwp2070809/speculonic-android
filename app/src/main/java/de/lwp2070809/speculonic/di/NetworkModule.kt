package de.lwp2070809.speculonic.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.BuildConfig
import de.lwp2070809.speculonic.SpeculonicApp
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.UpdateManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.NetworkEvent
import de.lwp2070809.speculonic.network.ServerReachableManager as NetworkServerReachableManager
import de.lwp2070809.speculonic.network.SubsonicServiceFactory
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.interceptor.CacheOverrideInterceptor
import de.lwp2070809.speculonic.network.interceptor.SonicLogInterceptor
import de.lwp2070809.speculonic.network.ssl.DynamicSslTrustManager
import de.lwp2070809.speculonic.util.AppConstants
import de.lwp2070809.speculonic.util.ConnectivityManagerNetworkMonitor
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.NetworkMonitor
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GithubHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamHttpClient

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryEntryPoint {
    fun subsonicRepository(): SubsonicRepository
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val DEFAULT_TIMEOUT = 30L

    // 向后兼容旧引用
    val ServerReachableManager = NetworkServerReachableManager

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val ipv4PreferredDns = Dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        addresses.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
    }

    private fun registerNetworkChangeObserver() {
        val app = try {
            SpeculonicApp.instance
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
                    LogManager.i("NetworkModule: Network state changed. Resetting Reachable status.")
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
            LogManager.i("NetworkModule: Successfully registered default network callback.")
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
            DynamicSslTrustManager.allowInsecureConnections || defaultHostnameVerifier.verify(hostname, session)
        }

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(dynamicHostnameVerifier)

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
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

    private val subsonicServiceFactory: SubsonicServiceFactory by lazy {
        SubsonicServiceFactory(okHttpClientInstance, json)
    }

    fun rebuildClientIfNeeded(allowInsecureConnections: Boolean) {
        if (DynamicSslTrustManager.allowInsecureConnections != allowInsecureConnections) {
            DynamicSslTrustManager.allowInsecureConnections = allowInsecureConnections
            LogManager.i("NetworkModule: Security mode switched, allowInsecureConnections=$allowInsecureConnections")
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = okHttpClientInstance

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
    fun provideStreamOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        return okHttpClientInstance.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val path = url.encodedPath
            val isMediaRequest = path.contains("rest/stream") || path.contains("rest/download")

            val finalRequest = if (isMediaRequest && url.queryParameter("u") == null) {
                val entryPoint = EntryPoints.get(context.applicationContext, RepositoryEntryPoint::class.java)
                val authParams = entryPoint.subsonicRepository().getCurrentAuthParams()
                if (authParams != null) {
                    val (u, t, s) = authParams
                    val newUrl = url.newBuilder()
                        .addQueryParameter("u", u)
                        .addQueryParameter("t", t)
                        .addQueryParameter("s", s)
                        .addQueryParameter("v", AppConstants.SUBSONIC_API_VERSION)
                        .addQueryParameter("c", AppConstants.SUBSONIC_CLIENT_ID)
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

                if (!isSuccessful || isExplicitTextOrError) {
                    val bodyString = try {
                        response.peekBody(10240).string()
                    } catch (e: Exception) {
                        "Failed to read body: ${e.message}"
                    }
                    LogManager.e("Stream/Download request returned non-audio response: code=${response.code}, contentType=$contentType, body=$bodyString")
                    val errorMessage = extractErrorMessage(bodyString)
                    throw IOException("Subsonic server returned error (code ${response.code}): $errorMessage")
                }
            }
            response
        }.build()
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor = ConnectivityManagerNetworkMonitor(context)

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
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        @GithubHttpClient okHttpClient: OkHttpClient
    ): UpdateManager = UpdateManager(context, preferencesManager, okHttpClient)

    fun provideSubsonicService(baseUrl: String): SubsonicService {
        return subsonicServiceFactory.getOrCreateService(baseUrl)
    }
}
