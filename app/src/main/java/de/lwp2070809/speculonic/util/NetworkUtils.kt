package de.lwp2070809.speculonic.util

import java.net.URI

object NetworkUtils {
    /**
     * 判断给定 URL 的主机地址是否为局域网/私有本地网络地址。
     * 包括 RFC 1918 私有 IP 网段 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)、
     * 回环地址 (127.0.0.0/8, localhost, ::1)、链路本地 (169.254.0.0/16, fe80::/10)、
     * 唯一本地 IPv6 (fc00::/7) 以及常见的 .local / .lan 等本地域名后缀。
     */
    fun isLocalNetworkAddress(urlString: String): Boolean {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) return false

        val host = try {
            val uri = URI.create(if (!trimmed.contains("://")) "http://$trimmed" else trimmed)
            uri.host ?: return false
        } catch (_: Exception) {
            return false
        }

        val lowerHost = host.lowercase()
        if (lowerHost == "localhost" ||
            lowerHost.endsWith(".local") ||
            lowerHost.endsWith(".lan") ||
            lowerHost.endsWith(".home") ||
            lowerHost.endsWith(".internal")
        ) {
            return true
        }

        val ipv4Parts = lowerHost.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.all { c -> c.isDigit() } }) {
            val p0 = ipv4Parts[0].toIntOrNull() ?: return false
            val p1 = ipv4Parts[1].toIntOrNull() ?: return false
            if (p0 == 10) return true
            if (p0 == 127) return true
            if (p0 == 192 && p1 == 168) return true
            if (p0 == 172 && p1 in 16..31) return true
            if (p0 == 169 && p1 == 254) return true
        }

        if (lowerHost == "::1" || lowerHost.startsWith("fc") || lowerHost.startsWith("fd") || lowerHost.startsWith("fe80")) {
            return true
        }

        return false
    }
}
