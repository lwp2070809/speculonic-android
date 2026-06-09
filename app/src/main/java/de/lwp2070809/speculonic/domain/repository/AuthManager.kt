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
    private val CACHE_DURATION = 30_000L 

    
    @Synchronized
    fun getAuthParams(forceRefresh: Boolean = false, isForImageContent: Boolean = false): Triple<String, String, String> {
        val currentTime = System.currentTimeMillis()
        val currentCache = cachedParams
        
        if (!forceRefresh && isForImageContent && currentCache != null && (currentTime - lastParamsTime) < CACHE_DURATION) {
            return currentCache
        }

        val s = UUID.randomUUID().toString().take(8)
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
        val passBytes = ByteArray(passChars.size)
        for (i in passChars.indices) {
            passBytes[i] = passChars[i].code.toByte()
        }
        val saltBytes = salt.toByteArray()
        md.update(passBytes)
        md.update(saltBytes)
        Arrays.fill(passBytes, 0.toByte())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
