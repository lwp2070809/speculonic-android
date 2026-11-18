package de.lwp2070809.speculonic.domain.repository

import java.security.MessageDigest
import java.util.Arrays
import java.util.UUID

class AuthManager(
    private val user: String,
    private val pass: CharArray
) {
    
    private var cachedParams: Triple<String, String, String>? = null
    private var lastParamsTime: Long = 0

    @Synchronized
    fun getAuthParams(forceRefresh: Boolean = false, isForImageContent: Boolean = false): Triple<String, String, String> {
        val currentTime = System.currentTimeMillis()
        val currentCache = cachedParams
        val canUseCache = !forceRefresh && isForImageContent && currentCache != null &&
            (currentTime - lastParamsTime) < CACHE_DURATION_MS
        if (canUseCache) {
            return currentCache
        }

        val s = UUID.randomUUID().toString().take(SALT_LENGTH)
        val t = md5WithSalt(pass, s)
        val newParams = Triple(user, t, s)
        
        if (isForImageContent) {
            cachedParams = newParams
            lastParamsTime = currentTime
        }
        
        return newParams
    }

    fun clearPassword() {
        Arrays.fill(pass, '0')
    }

    private fun md5WithSalt(passChars: CharArray, salt: String): String {
        val md = MessageDigest.getInstance("MD5")
        val charBuffer = java.nio.CharBuffer.wrap(passChars)
        val byteBuffer = java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(charBuffer)
        try {
            md.update(byteBuffer)
            val saltBytes = salt.toByteArray(Charsets.UTF_8)
            md.update(saltBytes)
        } finally {
            if (byteBuffer.hasArray()) {
                Arrays.fill(byteBuffer.array(), 0.toByte())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CACHE_DURATION_MS = 30_000L
        const val SALT_LENGTH = 8
    }
}
