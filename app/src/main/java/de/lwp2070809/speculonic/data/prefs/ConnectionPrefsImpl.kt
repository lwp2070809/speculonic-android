@file:Suppress("DEPRECATION")

package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.lwp2070809.speculonic.data.dataStore
import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import de.lwp2070809.speculonic.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class ConnectionPrefsImpl(private val context: Context) : ConnectionPrefs {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val SERVER_CONFIG_VERSION = intPreferencesKey("server_config_version")
        private val TRUST_ALL_CERTIFICATES = booleanPreferencesKey("trust_all_certificates")
        private val SERVER_CAPABILITIES_JSON = stringPreferencesKey("server_capabilities_json")
        private val LAST_PING_TIME = longPreferencesKey("last_ping_time")
        private val OFFLINE_MODE_ENABLED = booleanPreferencesKey("offline_mode_enabled")
        private val AUTO_OFFLINE_ON_METERED = booleanPreferencesKey("auto_offline_on_metered")

        private const val ENC_SERVER_URL = "enc_server_url"
        private const val ENC_USERNAME = "enc_username"
        private const val ENC_PASSWORD = "enc_password"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "speculonic_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("speculonic_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private val encryptedCache = ConcurrentHashMap<String, String>()

    private fun readEncryptedOrMigrate(encKey: String, dsKey: Preferences.Key<String>): Flow<String> {
        return context.dataStore.data.map { preferences ->
            val cachedValue = encryptedCache[encKey]
            if (!cachedValue.isNullOrEmpty()) {
                cachedValue
            } else {
                val encValue = encryptedPrefs.getString(encKey, null)
                if (!encValue.isNullOrEmpty()) {
                    encryptedCache[encKey] = encValue
                    encValue
                } else {
                    val isMigrated = encryptedPrefs.getBoolean("migrated_$encKey", false)
                    if (isMigrated) {
                        ""
                    } else {
                        val oldValue = preferences[dsKey] ?: ""
                        if (oldValue.isNotEmpty()) {
                            encryptedPrefs.edit().putString(encKey, oldValue).putBoolean("migrated_$encKey", true).apply()
                            encryptedCache[encKey] = oldValue
                        } else {
                            encryptedPrefs.edit().putBoolean("migrated_$encKey", true).apply()
                        }
                        oldValue
                    }
                }
            }
        }
    }

    override val serverUrl: Flow<String> = readEncryptedOrMigrate(ENC_SERVER_URL, SERVER_URL)
    override val username: Flow<String> = readEncryptedOrMigrate(ENC_USERNAME, USERNAME)
    override val password: Flow<String> = readEncryptedOrMigrate(ENC_PASSWORD, PASSWORD)

    override fun getServerUrlSync(): String {
        return encryptedCache[ENC_SERVER_URL]
            ?: encryptedPrefs.getString(ENC_SERVER_URL, null)?.also { encryptedCache[ENC_SERVER_URL] = it }
            ?: ""
    }

    override fun getUsernameSync(): String {
        return encryptedCache[ENC_USERNAME]
            ?: encryptedPrefs.getString(ENC_USERNAME, null)?.also { encryptedCache[ENC_USERNAME] = it }
            ?: ""
    }

    override fun getPasswordSync(): CharArray {
        val passStr = encryptedCache[ENC_PASSWORD]
            ?: encryptedPrefs.getString(ENC_PASSWORD, null)?.also { encryptedCache[ENC_PASSWORD] = it }
            ?: ""
        return passStr.toCharArray()
    }

    override suspend fun saveServerSettings(url: String, user: String, pass: String) {
        encryptedPrefs.edit()
            .putString(ENC_SERVER_URL, url)
            .putString(ENC_USERNAME, user)
            .putString(ENC_PASSWORD, pass)
            .apply()

        encryptedCache[ENC_SERVER_URL] = url
        encryptedCache[ENC_USERNAME] = user
        encryptedCache[ENC_PASSWORD] = pass

        context.dataStore.edit { preferences ->
            preferences.remove(SERVER_URL)
            preferences.remove(USERNAME)
            preferences.remove(PASSWORD)
            val currentVer = preferences[SERVER_CONFIG_VERSION] ?: 0
            preferences[SERVER_CONFIG_VERSION] = currentVer + 1
        }
    }

    override val trustAllCertificates: Flow<Boolean> = context.dataStore.data.map { it[TRUST_ALL_CERTIFICATES] ?: false }

    override suspend fun saveTrustAllCertificates(trust: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TRUST_ALL_CERTIFICATES] = trust
        }
        setTrustAllCertificatesSync(trust)
    }

    override fun getTrustAllCertificatesSync(): Boolean {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getBoolean("trust_all_certificates", false)
    }

    override fun setTrustAllCertificatesSync(trust: Boolean) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("trust_all_certificates", trust).apply()
    }

    override val serverCapabilities: Flow<ServerCapabilities?> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[SERVER_CAPABILITIES_JSON]
        try {
            jsonString?.let { Json.decodeFromString(ServerCapabilities.serializer(), it) }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveServerCapabilities(caps: ServerCapabilities?) {
        val jsonString = caps?.let { Json.encodeToString(ServerCapabilities.serializer(), it) }
        context.dataStore.edit { preferences ->
            if (jsonString == null) {
                preferences.remove(SERVER_CAPABILITIES_JSON)
            } else {
                preferences[SERVER_CAPABILITIES_JSON] = jsonString
            }
        }
        setServerCapabilitiesSync(caps)
    }

    override fun getServerCapabilitiesSync(): ServerCapabilities? {
        val jsonString = context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getString("server_capabilities_json", null)
        return try {
            jsonString?.let { Json.decodeFromString(ServerCapabilities.serializer(), it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun setServerCapabilitiesSync(caps: ServerCapabilities?) {
        val jsonString = caps?.let { Json.encodeToString(ServerCapabilities.serializer(), it) }
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putString("server_capabilities_json", jsonString).apply()
    }

    override val lastPingTime: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[LAST_PING_TIME] ?: 0L
    }

    override suspend fun saveLastPingTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_PING_TIME] = time
        }
        setLastPingTimeSync(time)
    }

    override fun getLastPingTimeSync(): Long {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getLong("last_ping_time", 0L)
    }

    override fun setLastPingTimeSync(time: Long) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putLong("last_ping_time", time).apply()
    }

    override val offlineModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[OFFLINE_MODE_ENABLED] ?: false
    }

    override suspend fun saveOfflineModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[OFFLINE_MODE_ENABLED] = enabled
        }
    }

    override val autoOfflineOnMetered: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_OFFLINE_ON_METERED] ?: false
    }

    override suspend fun saveAutoOfflineOnMetered(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_OFFLINE_ON_METERED] = enabled
        }
    }
}
