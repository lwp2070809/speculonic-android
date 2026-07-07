package de.lwp2070809.speculonic.data.prefs

import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import kotlinx.coroutines.flow.Flow

interface ConnectionPrefs {
    val serverUrl: Flow<String>
    val username: Flow<String>
    val password: Flow<String>
    val serverCapabilities: Flow<ServerCapabilities?>
    val lastPingTime: Flow<Long>
    val allowInsecureConnections: Flow<Boolean>
    val offlineModeEnabled: Flow<Boolean>
    val autoOfflineOnMetered: Flow<Boolean>

    fun getServerUrlSync(): String
    fun getUsernameSync(): String
    fun getPasswordSync(): CharArray

    fun getServerCapabilitiesSync(): ServerCapabilities?
    fun setServerCapabilitiesSync(caps: ServerCapabilities?)

    fun getLastPingTimeSync(): Long
    fun setLastPingTimeSync(time: Long)

    fun getAllowInsecureConnectionsSync(): Boolean
    fun setAllowInsecureConnectionsSync(allow: Boolean)

    suspend fun saveServerSettings(url: String, user: String, pass: String)
    suspend fun saveServerCapabilities(caps: ServerCapabilities?)
    suspend fun saveLastPingTime(time: Long)
    suspend fun saveAllowInsecureConnections(allow: Boolean)
    suspend fun saveOfflineModeEnabled(enabled: Boolean)
    suspend fun saveAutoOfflineOnMetered(enabled: Boolean)
}
