package de.lwp2070809.speculonic.network.ssl

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
object DynamicSslTrustManager : X509TrustManager {

    @Volatile
    var allowInsecureConnections: Boolean = false

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (allowInsecureConnections) {
            return
        }
        systemTrustManager.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return if (allowInsecureConnections) arrayOf() else systemTrustManager.acceptedIssuers
    }

    private val trustAllManager: X509TrustManager by lazy {
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
    }

    /**
     * 按传入策略返回独立的 TrustManager，供测试连接等隔离场景使用，避免读写全局状态。
     */
    fun createTrustManager(allowInsecure: Boolean): X509TrustManager {
        return if (allowInsecure) trustAllManager else systemTrustManager
    }

    /**
     * 按传入策略构造 HostnameVerifier，复用系统默认校验逻辑并支持动态或固定放行判定。
     */
    fun createHostnameVerifier(allowInsecureProvider: () -> Boolean): javax.net.ssl.HostnameVerifier {
        val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        return javax.net.ssl.HostnameVerifier { hostname, session ->
            allowInsecureProvider() || defaultVerifier.verify(hostname, session)
        }
    }
}
