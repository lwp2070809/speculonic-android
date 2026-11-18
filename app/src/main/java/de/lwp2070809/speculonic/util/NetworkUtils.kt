package de.lwp2070809.speculonic.util

import java.net.InetAddress
import java.net.URI

object NetworkUtils {
    private const val IPV4_PART_COUNT = 4
    private const val RFC1918_CLASS_A_FIRST = 10
    private const val LOOPBACK_FIRST = 127
    private const val RFC1918_CLASS_B_FIRST = 172
    private const val RFC1918_CLASS_B_MIN_SECOND = 16
    private const val RFC1918_CLASS_B_MAX_SECOND = 31
    private const val LINK_LOCAL_FIRST = 169
    private const val LINK_LOCAL_SECOND = 254
    private const val RFC1918_CLASS_C_FIRST = 192
    private const val RFC1918_CLASS_C_SECOND = 168

    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal")

    /**
     * 判断给定 URL 的主机地址是否为局域网/私有本地网络地址。
     * 包括 RFC 1918 私有 IP 网段 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)、
     * 回环地址 (127.0.0.0/8, localhost, ::1)、链路本地 (169.254.0.0/16, fe80::/10)、
     * 唯一本地 IPv6 (fc00::/7) 以及常见的 .local / .lan 等本地域名后缀。
     */
    fun isLocalNetworkAddress(urlString: String): Boolean {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) return false

        val host = extractLowerHost(trimmed) ?: return false
        return isLocalHostname(host) || isLocalIpv4(host) || isLocalIpv6(host)
    }

    private fun extractLowerHost(urlString: String): String? {
        return try {
            val uri = URI.create(if (!urlString.contains("://")) "http://$urlString" else urlString)
            uri.host?.removeSurrounding("[", "]")?.lowercase()
        } catch (e: IllegalArgumentException) {
            LogManager.d("NetworkUtils: failed to parse URI host: ${e.message}")
            null
        }
    }

    private fun isLocalHostname(lowerHost: String): Boolean {
        val host = lowerHost.removeSuffix(".")
        return host == "localhost" || localSuffixes.any { host.endsWith(it) }
    }

    private fun isLocalIpv4(lowerHost: String): Boolean {
        val ipv4Parts = lowerHost.split(".")
        if (ipv4Parts.size != IPV4_PART_COUNT || !ipv4Parts.all { part -> part.all { it.isDigit() } }) {
            return false
        }
        val p0 = ipv4Parts[0].toIntOrNull()
        val p1 = ipv4Parts[1].toIntOrNull()
        return p0 != null && p1 != null && isPrivateIpv4Octets(p0, p1)
    }

    private fun isPrivateIpv4Octets(p0: Int, p1: Int): Boolean = when (p0) {
        RFC1918_CLASS_A_FIRST, LOOPBACK_FIRST -> true
        RFC1918_CLASS_C_FIRST -> p1 == RFC1918_CLASS_C_SECOND
        RFC1918_CLASS_B_FIRST -> p1 in RFC1918_CLASS_B_MIN_SECOND..RFC1918_CLASS_B_MAX_SECOND
        LINK_LOCAL_FIRST -> p1 == LINK_LOCAL_SECOND
        else -> false
    }

    private fun isLocalIpv6(lowerHost: String): Boolean {
        if (':' !in lowerHost) return false
        val address = InetAddress.getByName(lowerHost.substringBefore('%'))
        val uniqueLocalPrefix = 0xfc
        val prefixMask = 0xfe
        return address.isLoopbackAddress || address.isLinkLocalAddress ||
            (address.address.size == IPV6_ADDRESS_BYTES && address.address[0].toInt() and prefixMask == uniqueLocalPrefix)
    }

    private const val IPV6_ADDRESS_BYTES = 16
}
