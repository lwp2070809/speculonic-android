package de.lwp2070809.speculonic.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.UpdateManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
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
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
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
    private const val ERROR_BODY_PREVIEW_LENGTH = 200

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val ipv4PreferredDns = Dns { hostname ->
        val addresses = Dns.SYSTEM.lookup(hostname)
        addresses.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
    }

    @Volatile
    private var isClientInitialized = false

    @Volatile
    private var isServiceFactoryInitialized = false

    private val okHttpClientInstance: OkHttpClient by lazy {
        buildServerClientBuilder(
            trustManager = DynamicSslTrustManager,
            hostnameVerifier = DynamicSslTrustManager.createHostnameVerifier {
                DynamicSslTrustManager.allowInsecureConnections
            },
            allowInsecureProvider = { DynamicSslTrustManager.allowInsecureConnections }
        ).build().also { isClientInitialized = true }
    }

    private fun buildServerClientBuilder(
        trustManager: javax.net.ssl.X509TrustManager,
        hostnameVerifier: javax.net.ssl.HostnameVerifier,
        allowInsecureProvider: () -> Boolean,
        trackReachability: Boolean = true
    ): OkHttpClient.Builder {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return OkHttpClient.Builder()
            .addInterceptor(SonicLogInterceptor(allowInsecureProvider, trackReachability))
            .addNetworkInterceptor(CacheOverrideInterceptor())
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .dns(ipv4PreferredDns)
    }

    private val subsonicServiceFactory: SubsonicServiceFactory by lazy {
        isServiceFactoryInitialized = true
        SubsonicServiceFactory(okHttpClientInstance, json)
    }

    /**
     * 动态切换全局不安全连接模式。
     * 当从启用不安全连接切换为关闭（true -> false）时，立即掐断全部在途请求并驱逐连接池，
     * 防止既有 Keep-Alive 或 TLS 会话继续被复用。
     *
     * @return 本次切换是否已触发连接池驱逐与在途请求取消。
     */
    @Synchronized
    fun rebuildClientIfNeeded(allowInsecureConnections: Boolean): Boolean {
        val wasInsecure = DynamicSslTrustManager.allowInsecureConnections
        if (wasInsecure != allowInsecureConnections) {
            DynamicSslTrustManager.allowInsecureConnections = allowInsecureConnections
            LogManager.i("NetworkModule: Security mode switched, allowInsecureConnections=$allowInsecureConnections")
            if (wasInsecure && !allowInsecureConnections) {
                cancelAndEvictConnections()
                return true
            }
        }
        return false
    }

    /**
     * 立即取消所有在途请求并驱逐共享连接池中的所有空闲与不可复用连接。
     * 由于 @StreamHttpClient 通过 okHttpClientInstance.newBuilder() 派生并共享 Dispatcher/ConnectionPool，
     * 此操作会同步清理 API、图片、播放流及下载连接；@GithubHttpClient 为独立实例不受影响。
     * 调用方需确保在后台 IO 线程执行，避免在主线程触发同步 Socket 关闭。
     */
    fun cancelAndEvictConnections() {
        if (!isClientInitialized) return
        evictClientConnections(okHttpClientInstance, shutdownExecutor = false)
        LogManager.i("NetworkModule: Cancelled in-flight calls and evicted shared connection pool")
    }

    fun clearSubsonicServiceCache() {
        if (!isServiceFactoryInitialized) return
        subsonicServiceFactory.clearCache()
    }

    internal fun evictClientConnections(client: OkHttpClient, shutdownExecutor: Boolean) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        if (shutdownExecutor) {
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * 为配置弹窗「测试连接」创建与全局状态及连接池完全隔离的一次性客户端与服务。
     * 测试结束后由调用方负责销毁该客户端，防止测试连接或草稿安全策略污染全局运行态。
     */
    fun createTestSubsonicServiceClient(
        baseUrl: String,
        allowInsecureConnections: Boolean
    ): Pair<SubsonicService, OkHttpClient> {
        val testClient = buildServerClientBuilder(
            trustManager = DynamicSslTrustManager.createTrustManager(allowInsecureConnections),
            hostnameVerifier = DynamicSslTrustManager.createHostnameVerifier { allowInsecureConnections },
            allowInsecureProvider = { allowInsecureConnections },
            trackReachability = false
        )
            // 测试连接使用一次性无复用连接池，严禁污染全局连接池或复用旧会话
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()
        val service = SubsonicServiceFactory.createUncachedService(baseUrl, testClient, json)
        return service to testClient
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
            LogManager.w("NetworkModule: Failed to parse error message from response body", e)
        }
        return body.take(ERROR_BODY_PREVIEW_LENGTH)
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
                val authParams = entryPoint.subsonicRepository().getMediaAuthParams(url)
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
                        response.use { it.peekBody(10240).string() }
                    } catch (e: Exception) {
                        "Failed to read body: ${e.message}"
                    }
                    val errorMessage = extractErrorMessage(bodyString)
                    LogManager.e(
                        "Stream/Download request returned non-audio response: code=${response.code}, " +
                            "contentType=$contentType, detail=$errorMessage"
                    )
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
    ): NetworkMonitor = ConnectivityManagerNetworkMonitor(
        context = context,
        scope = de.lwp2070809.speculonic.SpeculonicApp.applicationScope
    )

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
