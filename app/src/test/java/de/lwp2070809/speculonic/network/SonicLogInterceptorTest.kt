package de.lwp2070809.speculonic.network

import de.lwp2070809.speculonic.network.interceptor.SonicLogInterceptor
import org.junit.Assert.assertEquals
import org.junit.Test

class SonicLogInterceptorTest {

    @Test
    fun maskSensitiveUrl_masksAuthParameters() {
        val originalUrl = "https://example.com/rest/ping?u=admin&t=token123&s=salt456&v=1.16.1&c=Speculonic"
        val expected = "https://example.com/rest/ping?u=***&t=***&s=***&v=1.16.1&c=Speculonic"
        val result = SonicLogInterceptor.maskSensitiveUrl(originalUrl)
        assertEquals(expected, result)
    }

    @Test
    fun maskSensitiveUrl_masksPasswordParameter() {
        val originalUrl = "https://example.com/rest/getSong?id=song_99&u=user1&p=super_secret_password"
        val expected = "https://example.com/rest/getSong?id=song_99&u=***&p=***"
        val result = SonicLogInterceptor.maskSensitiveUrl(originalUrl)
        assertEquals(expected, result)
    }

    @Test
    fun maskSensitiveUrl_preservesBusinessParametersWithMatchingSuffix() {
        val originalUrl = "https://example.com/rest/search3?query=rock&songCount=500&albumCount=20&artistCount=10&offset=0&format=flac&artist=Queen"
        val result = SonicLogInterceptor.maskSensitiveUrl(originalUrl)
        // 确保包含以 u/t/p/s 结尾的参数名（如 songCount, albumCount, offset, format）不被正则误伤
        assertEquals(originalUrl, result)
    }

    @Test
    fun maskSensitiveUrl_preservesUrlWithoutSensitiveParameters() {
        val originalUrl = "https://example.com/rest/getCoverArt?id=art_100&size=300"
        val result = SonicLogInterceptor.maskSensitiveUrl(originalUrl)
        assertEquals(originalUrl, result)
    }
}
