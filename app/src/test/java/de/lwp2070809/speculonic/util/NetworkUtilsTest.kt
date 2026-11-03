package de.lwp2070809.speculonic.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun testLocalNetworkAddresses() {
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://192.168.1.100:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("https://10.0.0.5:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://172.20.10.2:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://127.0.0.1:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://localhost:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://my-nas.local:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://subsonic.lan:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://server.home:4533"))
        assertTrue(NetworkUtils.isLocalNetworkAddress("http://169.254.1.1:4533"))
    }

    @Test
    fun testPublicAndExternalAddresses() {
        assertFalse(NetworkUtils.isLocalNetworkAddress("https://music.example.com"))
        assertFalse(NetworkUtils.isLocalNetworkAddress("https://8.8.8.8:4533"))
        assertFalse(NetworkUtils.isLocalNetworkAddress("https://1.1.1.1"))
        assertFalse(NetworkUtils.isLocalNetworkAddress("https://subsonic.org/api"))
        assertFalse(NetworkUtils.isLocalNetworkAddress(""))
        assertFalse(NetworkUtils.isLocalNetworkAddress("invalid-url"))
    }
}
