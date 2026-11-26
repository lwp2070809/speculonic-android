package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.dataStore
import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

private data class DecryptedCredentials(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = ""
)

class ConnectionPrefsImpl(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val settingsStore: DataStore<Preferences> = context.applicationContext.dataStore,
    private val secureDataStore: DataStore<Preferences> = getOrCreateSecureDataStore(context.applicationContext)
) : ConnectionPrefs {

    private val appContext: Context = context.applicationContext

    private val initScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val credentialsMutex = Mutex()

    // 以 null 表示凭据尚未在后台完成解密，避免主线程 Flow 收集触发同步阻塞或发出伪空值
    private val credentialsState = MutableStateFlow<DecryptedCredentials?>(null)
    private val _credentialsResetWarning = MutableStateFlow(false)
    override val credentialsResetWarning: StateFlow<Boolean> = _credentialsResetWarning.asStateFlow()

    init {
        initScope.launch {
            var retryDelayMs = INIT_RETRY_MS
            var attempts = 0
            while (credentialsState.value == null && attempts < MAX_INIT_RETRIES) {
                tryInitializeCredentials()
                if (credentialsState.value == null) {
                    attempts++
                    if (attempts < MAX_INIT_RETRIES) {
                        delay(retryDelayMs)
                        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_INIT_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private suspend fun tryInitializeCredentials(): DecryptedCredentials? = try {
        readCredentials()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogManager.w("ConnectionPrefs: Credential storage temporarily unavailable", e)
        null
    }

    private suspend fun readCredentials(): DecryptedCredentials {
        credentialsState.value?.let { return it }
        // The cache reset can cancel and drain playback initialization, which also reads credentials.
        return initScope.async {
            credentialsMutex.withLock {
                credentialsState.value ?: loadStoredCredentials().also { credentialsState.value = it }
            }
        }.await()
    }

    private fun currentCredentialsSync(): DecryptedCredentials {
        credentialsState.value?.let { return it }
        return runBlocking(ioDispatcher) {
            tryInitializeCredentials() ?: DecryptedCredentials()
        }
    }

    private fun <T> credentialsFlow(selector: (DecryptedCredentials) -> T): Flow<T> = flow {
        tryInitializeCredentials()
        emitAll(credentialsState.filterNotNull().map(selector))
    }.distinctUntilChanged()

    private suspend fun loadStoredCredentials(): DecryptedCredentials {
        val currentPrefs = secureDataStore.data.first()
        val encUrl = currentPrefs[SEC_SERVER_URL]
        val encUser = currentPrefs[SEC_USERNAME]
        val encPass = currentPrefs[SEC_PASSWORD]
        if (encUrl != null || encUser != null || encPass != null) {
            return decryptStoredCredentials(encUrl, encUser, encPass)
        }
        _credentialsResetWarning.value = settingsStore.data.first()[CREDENTIALS_RESET_PENDING] == true
        return DecryptedCredentials()
    }

    private suspend fun decryptStoredCredentials(
        encUrl: String?,
        encUser: String?,
        encPass: String?
    ): DecryptedCredentials {
        val credentials = try {
            val url = encUrl?.let { KeystoreAesGcmCipher.decrypt(it) } ?: ""
            val user = encUser?.let { KeystoreAesGcmCipher.decrypt(it) } ?: ""
            val pass = encPass?.let { KeystoreAesGcmCipher.decrypt(it) } ?: ""
            DecryptedCredentials(url, user, pass)
        } catch (e: Exception) {
            if (e is CancellationException || !KeystoreAesGcmCipher.isPermanentFailure(e)) throw e
            LogManager.e("ConnectionPrefs: Failed to decrypt Keystore DataStore credentials; wiping corrupted state", e)
            wipeCorruptedCredentials()
            return DecryptedCredentials()
        }
        ensureAccountFingerprintStored(credentials.serverUrl, credentials.username)
        clearCredentialsResetPendingIfPresent()
        return credentials
    }

    private suspend fun clearCredentialsResetPendingIfPresent() {
        if (settingsStore.data.first()[CREDENTIALS_RESET_PENDING] == true) {
            settingsStore.edit { it.remove(CREDENTIALS_RESET_PENDING) }
        }
    }

    private suspend fun ensureAccountFingerprintStored(url: String, user: String) {
        val fingerprint = computeAccountFingerprint(url, user)
        if (fingerprint.isEmpty()) return
        val existing = settingsStore.data.first()[LAST_ACCOUNT_FINGERPRINT]
        if (existing != fingerprint) {
            settingsStore.edit { it[LAST_ACCOUNT_FINGERPRINT] = fingerprint }
        }
    }

    private suspend fun wipeCorruptedCredentials() {
        secureDataStore.edit { it.clear() }
        KeystoreAesGcmCipher.deleteKeyQuietly()
        settingsStore.edit { it[CREDENTIALS_RESET_PENDING] = true }
        _credentialsResetWarning.value = true
    }

    private suspend fun writeEncryptedToDataStore(url: String, user: String, pass: String) {
        val encUrl = KeystoreAesGcmCipher.encrypt(url)
        val encUser = KeystoreAesGcmCipher.encrypt(user)
        val encPass = KeystoreAesGcmCipher.encrypt(pass)
        secureDataStore.edit { prefs ->
            prefs[SEC_SERVER_URL] = encUrl
            prefs[SEC_USERNAME] = encUser
            prefs[SEC_PASSWORD] = encPass
        }
    }

    override val serverUrl: Flow<String> = credentialsFlow { it.serverUrl }

    override val username: Flow<String> = credentialsFlow { it.username }

    override val password: Flow<String> = credentialsFlow { it.password }

    override fun getServerUrlSync(): String = currentCredentialsSync().serverUrl

    override fun getUsernameSync(): String = currentCredentialsSync().username

    override fun getPasswordSync(): CharArray = currentCredentialsSync().password.toCharArray()

    override suspend fun readServerIdentity(): Pair<String, String> =
        readCredentials().let { it.serverUrl to it.username }

    override suspend fun readLastAccountFingerprint(): String? =
        settingsStore.data.first()[LAST_ACCOUNT_FINGERPRINT]?.takeIf { it.isNotBlank() }

    override suspend fun saveServerSettings(url: String, user: String, pass: String) {
        credentialsMutex.withLock {
            withContext(ioDispatcher) {
                writeEncryptedToDataStore(url, user, pass)
                credentialsState.value = DecryptedCredentials(url, user, pass)
                _credentialsResetWarning.value = false

                val fingerprint = computeAccountFingerprint(url, user)
                settingsStore.edit { preferences ->
                    preferences.remove(CREDENTIALS_RESET_PENDING)
                    if (fingerprint.isEmpty()) {
                        preferences.remove(LAST_ACCOUNT_FINGERPRINT)
                    } else {
                        preferences[LAST_ACCOUNT_FINGERPRINT] = fingerprint
                    }
                    val currentVer = preferences[SERVER_CONFIG_VERSION] ?: 0
                    preferences[SERVER_CONFIG_VERSION] = currentVer + 1
                }
            }
        }
    }

    override val allowInsecureConnections: Flow<Boolean> =
        settingsStore.data.map { it[TRUST_ALL_CERTIFICATES] ?: false }.distinctUntilChanged()

    override suspend fun saveAllowInsecureConnections(allow: Boolean) {
        settingsStore.edit { preferences ->
            preferences[TRUST_ALL_CERTIFICATES] = allow
        }
        setAllowInsecureConnectionsSync(allow)
    }

    override fun getAllowInsecureConnectionsSync(): Boolean {
        return appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("trust_all_certificates", false)
    }

    override fun setAllowInsecureConnectionsSync(allow: Boolean) {
        appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("trust_all_certificates", allow).apply()
    }

    private fun decodeCapabilitiesOrNull(jsonString: String?): ServerCapabilities? {
        if (jsonString == null) return null
        return try {
            Json.decodeFromString(ServerCapabilities.serializer(), jsonString)
        } catch (e: Exception) {
            LogManager.w("ConnectionPrefs: Failed to decode cached ServerCapabilities", e)
            null
        }
    }

    override val serverCapabilities: Flow<ServerCapabilities?> = settingsStore.data.map { preferences ->
        decodeCapabilitiesOrNull(preferences[SERVER_CAPABILITIES_JSON])
    }.distinctUntilChanged()

    override suspend fun saveServerCapabilities(caps: ServerCapabilities?) {
        val jsonString = caps?.let { Json.encodeToString(ServerCapabilities.serializer(), it) }
        settingsStore.edit { preferences ->
            if (jsonString == null) {
                preferences.remove(SERVER_CAPABILITIES_JSON)
            } else {
                preferences[SERVER_CAPABILITIES_JSON] = jsonString
            }
        }
        setServerCapabilitiesSync(caps)
    }

    override fun getServerCapabilitiesSync(): ServerCapabilities? {
        val jsonString = appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("server_capabilities_json", null)
        return decodeCapabilitiesOrNull(jsonString)
    }

    override fun setServerCapabilitiesSync(caps: ServerCapabilities?) {
        val jsonString = caps?.let { Json.encodeToString(ServerCapabilities.serializer(), it) }
        appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("server_capabilities_json", jsonString).apply()
    }

    override val lastPingTime: Flow<Long> =
        settingsStore.data.map { it[LAST_PING_TIME] ?: 0L }.distinctUntilChanged()

    override suspend fun saveLastPingTime(time: Long) {
        settingsStore.edit { preferences ->
            preferences[LAST_PING_TIME] = time
        }
        setLastPingTimeSync(time)
    }

    override fun getLastPingTimeSync(): Long {
        return appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("last_ping_time", 0L)
    }

    override fun setLastPingTimeSync(time: Long) {
        appContext.getSharedPreferences(NETWORK_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong("last_ping_time", time).apply()
    }

    override val offlineModeEnabled: Flow<Boolean> =
        settingsStore.data.map { it[OFFLINE_MODE_ENABLED] ?: false }.distinctUntilChanged()

    override suspend fun saveOfflineModeEnabled(enabled: Boolean) {
        settingsStore.edit { preferences ->
            preferences[OFFLINE_MODE_ENABLED] = enabled
        }
    }

    override val autoOfflineOnMetered: Flow<Boolean> =
        settingsStore.data.map { it[AUTO_OFFLINE_ON_METERED] ?: false }.distinctUntilChanged()

    override suspend fun saveAutoOfflineOnMetered(enabled: Boolean) {
        settingsStore.edit { preferences ->
            preferences[AUTO_OFFLINE_ON_METERED] = enabled
        }
    }

    override suspend fun toggleManualOfflineMode(): Pair<Boolean, Boolean> {
        var wasAutoOffline = false
        var newOffline = false
        settingsStore.edit { preferences ->
            val currentOffline = preferences[OFFLINE_MODE_ENABLED] ?: false
            val currentAuto = preferences[AUTO_OFFLINE_ON_METERED] ?: false
            if (currentAuto) {
                preferences[AUTO_OFFLINE_ON_METERED] = false
                wasAutoOffline = true
            }
            newOffline = !currentOffline
            preferences[OFFLINE_MODE_ENABLED] = newOffline
        }
        return Pair(newOffline, wasAutoOffline)
    }

    companion object {
        private const val INIT_RETRY_MS = 5_000L
        private const val MAX_INIT_RETRY_DELAY_MS = 60_000L
        private const val MAX_INIT_RETRIES = 6
        private const val NETWORK_PREFS_NAME = "speculonic_network_prefs"
        private const val SECURE_DATASTORE_REL_PATH = "datastore/secure_connection.preferences_pb"

        private val LAST_ACCOUNT_FINGERPRINT = stringPreferencesKey("last_account_fingerprint")
        private val CREDENTIALS_RESET_PENDING = booleanPreferencesKey("credentials_reset_pending")
        private val SERVER_CONFIG_VERSION = intPreferencesKey("server_config_version")
        private val TRUST_ALL_CERTIFICATES = booleanPreferencesKey("trust_all_certificates")
        private val SERVER_CAPABILITIES_JSON = stringPreferencesKey("server_capabilities_json")
        private val LAST_PING_TIME = longPreferencesKey("last_ping_time")
        private val OFFLINE_MODE_ENABLED = booleanPreferencesKey("offline_mode_enabled")
        private val AUTO_OFFLINE_ON_METERED = booleanPreferencesKey("auto_offline_on_metered")

        private val SEC_SERVER_URL = stringPreferencesKey("sec_server_url")
        private val SEC_USERNAME = stringPreferencesKey("sec_username")
        private val SEC_PASSWORD = stringPreferencesKey("sec_password")

        @Volatile
        private var secureDataStoreInstance: DataStore<Preferences>? = null

        internal fun computeAccountFingerprint(url: String, user: String): String {
            val normalizedUrl = url.trim()
            val normalizedUser = user.trim()
            if (normalizedUrl.isEmpty() && normalizedUser.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$normalizedUrl\n$normalizedUser".toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun getOrCreateSecureDataStore(appContext: Context): DataStore<Preferences> {
            return secureDataStoreInstance ?: synchronized(this) {
                secureDataStoreInstance ?: PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    produceFile = { File(appContext.noBackupFilesDir, SECURE_DATASTORE_REL_PATH) }
                ).also { secureDataStoreInstance = it }
            }
        }
    }
}
