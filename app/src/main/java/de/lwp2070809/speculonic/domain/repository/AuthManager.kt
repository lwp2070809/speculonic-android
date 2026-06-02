package de.lwp2070809.speculonic.domain.repository

import java.security.MessageDigest
import java.util.UUID

class AuthManager(
    private val user: String,
    private val pass: String
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
        val t = md5(pass + s)
        val newParams = Triple(user, t, s)
        
        if (isForImageContent) {
            cachedParams = newParams
            lastParamsTime = currentTime
        }
        
        return newParams
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
