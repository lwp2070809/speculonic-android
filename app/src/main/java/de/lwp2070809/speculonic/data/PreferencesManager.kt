@file:Suppress("DEPRECATION")
package de.lwp2070809.speculonic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.lwp2070809.speculonic.domain.repository.ServerCapabilities
import de.lwp2070809.speculonic.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

enum class ColorMode {
    ALBUM_COVER, SYSTEM_COLOR
}

enum class PlayerBackgroundMode {
    GLOW_GRADIENT, GAUSSIAN_BLUR
}


class PreferencesManager(private val context: Context) {

    companion object {
        
        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }

        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val SERVER_CONFIG_VERSION = intPreferencesKey("server_config_version")
        val CACHE_LOCATION = stringPreferencesKey("cache_location")
        val MAX_CACHE_SIZE = longPreferencesKey("max_cache_size")
        val MOBILE_PLAY_ALLOWED = booleanPreferencesKey("mobile_play_allowed")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_MODE = stringPreferencesKey("color_mode")
        val SILENT_CACHE_ENABLED = booleanPreferencesKey("silent_cache_enabled")
        val AUTO_EXPORT_SILENT_CACHE = booleanPreferencesKey("auto_export_silent_cache")
        val AUTO_CLEAN_CACHE_AFTER_EXPORT = booleanPreferencesKey("auto_clean_cache_after_export")
        
        val LAST_SONG_ID = stringPreferencesKey("last_song_id")
        val LAST_POSITION = longPreferencesKey("last_position")
        val LAST_QUEUE_INDEX = intPreferencesKey("last_queue_index")
        val LAST_QUEUE_TITLE = stringPreferencesKey("last_queue_title")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val SHUFFLE_MODE = booleanPreferencesKey("shuffle_mode")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val LAST_FULL_SYNC_TIME = longPreferencesKey("last_full_sync_time")
        val SERVER_LAST_MODIFIED = longPreferencesKey("server_last_modified")
        val IS_SYNCING = booleanPreferencesKey("is_syncing")
        val SYNC_PROGRESS = stringPreferencesKey("sync_progress")
        val BACKGROUND_SYNC_ENABLED = booleanPreferencesKey("background_sync_enabled")
        val LAST_SLEEP_TIMER_MINUTES = intPreferencesKey("last_sleep_timer_minutes")
        val LAST_SLEEP_TIMER_SONG_COUNT = intPreferencesKey("last_sleep_timer_song_count")
        
        val CAR_BLUETOOTH_ENABLED = booleanPreferencesKey("car_bluetooth_enabled")
        val SYNC_PLAYBACK_STATE = booleanPreferencesKey("sync_playback_state")
        val BLUETOOTH_LYRICS_ENABLED = booleanPreferencesKey("bluetooth_lyrics_enabled")
        val BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR = booleanPreferencesKey("bluetooth_lyrics_hide_progress_bar")
        val BLUETOOTH_CAR_DEVICE_NAMES = androidx.datastore.preferences.core.stringSetPreferencesKey("bluetooth_car_device_names")
        
        val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        val REPLAY_GAIN_ENABLED = booleanPreferencesKey("replay_gain_enabled")

        val NOTIFICATION_REMINDER_ENABLED = booleanPreferencesKey("notification_reminder_enabled")
        val TRUST_ALL_CERTIFICATES = booleanPreferencesKey("trust_all_certificates")
        val USE_GAUSSIAN_BLUR_BACKGROUND = booleanPreferencesKey("use_gaussian_blur_background")
        val PLAYER_BACKGROUND_MODE = stringPreferencesKey("player_background_mode")
        
        val LAST_SEED_COLOR_LIGHT = intPreferencesKey("last_seed_color_light")
        val LAST_SEED_COLOR_DARK = intPreferencesKey("last_seed_color_dark")
        
        val SHOW_OFFLINE_TOAST = booleanPreferencesKey("show_offline_toast")
        val LAST_CACHE_SCAN_TIME = longPreferencesKey("last_cache_scan_time")
        
        val SERVER_CAPABILITIES_JSON = stringPreferencesKey("server_capabilities_json")
        val LAST_PING_TIME = longPreferencesKey("last_ping_time")
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
            
            LogLevel.entries 
            context.getSharedPreferences("speculonic_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private val encryptedCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private object EncryptedKeys {
        const val ENC_SERVER_URL = "enc_server_url"
        const val ENC_USERNAME = "enc_username"
        const val ENC_PASSWORD = "enc_password"
    }

    
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
                    
                    val oldValue = preferences[dsKey] ?: ""
                    if (oldValue.isNotEmpty()) {
                        encryptedPrefs.edit().putString(encKey, oldValue).apply()
                        encryptedCache[encKey] = oldValue
                        
                    }
                    oldValue
                }
            }
        }
    }

    val serverUrl: Flow<String> = readEncryptedOrMigrate(EncryptedKeys.ENC_SERVER_URL, SERVER_URL)

    val username: Flow<String> = readEncryptedOrMigrate(EncryptedKeys.ENC_USERNAME, USERNAME)

    val password: Flow<String> = readEncryptedOrMigrate(EncryptedKeys.ENC_PASSWORD, PASSWORD)

    
    fun getServerUrlSync(): String {
        return encryptedCache[EncryptedKeys.ENC_SERVER_URL]
            ?: encryptedPrefs.getString(EncryptedKeys.ENC_SERVER_URL, null)?.also { encryptedCache[EncryptedKeys.ENC_SERVER_URL] = it }
            ?: ""
    }
    fun getUsernameSync(): String {
        return encryptedCache[EncryptedKeys.ENC_USERNAME]
            ?: encryptedPrefs.getString(EncryptedKeys.ENC_USERNAME, null)?.also { encryptedCache[EncryptedKeys.ENC_USERNAME] = it }
            ?: ""
    }
    fun getPasswordSync(): String {
        return encryptedCache[EncryptedKeys.ENC_PASSWORD]
            ?: encryptedPrefs.getString(EncryptedKeys.ENC_PASSWORD, null)?.also { encryptedCache[EncryptedKeys.ENC_PASSWORD] = it }
            ?: ""
    }

    val cacheLocation: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CACHE_LOCATION] ?: ""
    }

    val maxCacheSize: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[MAX_CACHE_SIZE] ?: (1024L * 1024 * 1024) 
    }

    val mobilePlayAllowed: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MOBILE_PLAY_ALLOWED] ?: true
    }

    val logLevel: Flow<LogLevel> = context.dataStore.data.map { preferences ->
        val levelName = preferences[LOG_LEVEL] ?: LogLevel.INFO.name
        try {
            LogLevel.valueOf(levelName)
        } catch (e: Exception) {
            LogLevel.INFO
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[THEME_MODE] ?: ThemeMode.SYSTEM.name
        try {
            ThemeMode.valueOf(modeName)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    val colorMode: Flow<ColorMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[COLOR_MODE] ?: ColorMode.ALBUM_COVER.name
        try {
            ColorMode.valueOf(modeName)
        } catch (e: Exception) {
            ColorMode.ALBUM_COVER
        }
    }

    val silentCacheEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SILENT_CACHE_ENABLED] ?: true
    }

    val autoExportSilentCache: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_EXPORT_SILENT_CACHE] ?: false
    }

    val autoCleanCacheAfterExport: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CLEAN_CACHE_AFTER_EXPORT] ?: true
    }

    val lastPosition: Flow<Long> = context.dataStore.data.map { it[LAST_POSITION] ?: 0L }
    val lastQueueIndex: Flow<Int> = context.dataStore.data.map { it[LAST_QUEUE_INDEX] ?: 0 }
    val lastQueueTitle: Flow<String?> = context.dataStore.data.map { it[LAST_QUEUE_TITLE] }
    val repeatMode: Flow<Int> = context.dataStore.data.map { 
        val mode = it[REPEAT_MODE] ?: androidx.media3.common.Player.REPEAT_MODE_ALL
        if (mode == androidx.media3.common.Player.REPEAT_MODE_OFF) androidx.media3.common.Player.REPEAT_MODE_ALL else mode
    }
    val shuffleMode: Flow<Boolean> = context.dataStore.data.map { it[SHUFFLE_MODE] ?: false }
    
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[LAST_SYNC_TIME] ?: 0L }
    val lastFullSyncTime: Flow<Long> = context.dataStore.data.map { it[LAST_FULL_SYNC_TIME] ?: 0L }
    val serverLastModified: Flow<Long> = context.dataStore.data.map { it[SERVER_LAST_MODIFIED] ?: 0L }
    val isSyncing: Flow<Boolean> = context.dataStore.data.map { it[IS_SYNCING] ?: false }
    val syncProgress: Flow<String?> = context.dataStore.data.map { it[SYNC_PROGRESS] }
    val backgroundSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[BACKGROUND_SYNC_ENABLED] ?: false }
    val lastSleepTimerMinutes: Flow<Int> = context.dataStore.data.map { it[LAST_SLEEP_TIMER_MINUTES] ?: 30 }
    val lastSleepTimerSongCount: Flow<Int> = context.dataStore.data.map { it[LAST_SLEEP_TIMER_SONG_COUNT] ?: 10 }
    
    val carBluetoothEnabled: Flow<Boolean> = context.dataStore.data.map { it[CAR_BLUETOOTH_ENABLED] ?: false }
    val syncPlaybackState: Flow<Boolean> = context.dataStore.data.map { it[SYNC_PLAYBACK_STATE] ?: false }
    val skipSilenceEnabled: Flow<Boolean> = context.dataStore.data.map { it[SKIP_SILENCE_ENABLED] ?: false }
    val replayGainEnabled: Flow<Boolean> = context.dataStore.data.map { it[REPLAY_GAIN_ENABLED] ?: false }
    val bluetoothLyricsEnabled: Flow<Boolean> = context.dataStore.data.map { it[BLUETOOTH_LYRICS_ENABLED] ?: false }
    val bluetoothLyricsHideProgressBar: Flow<Boolean> = context.dataStore.data.map { it[BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR] ?: false }
    val bluetoothCarDeviceNames: Flow<Set<String>> = context.dataStore.data.map { it[BLUETOOTH_CAR_DEVICE_NAMES] ?: emptySet() }
    
    val notificationReminderEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATION_REMINDER_ENABLED] ?: true }
    val trustAllCertificates: Flow<Boolean> = context.dataStore.data.map { it[TRUST_ALL_CERTIFICATES] ?: false }
    val useGaussianBlurBackground: Flow<Boolean> = context.dataStore.data.map { it[USE_GAUSSIAN_BLUR_BACKGROUND] ?: false }
    val playerBackgroundMode: Flow<PlayerBackgroundMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[PLAYER_BACKGROUND_MODE]
        if (modeName != null) {
            try {
                PlayerBackgroundMode.valueOf(modeName)
            } catch (e: Exception) {
                PlayerBackgroundMode.GAUSSIAN_BLUR
            }
        } else {
            val legacyBlur = preferences[USE_GAUSSIAN_BLUR_BACKGROUND]
            if (legacyBlur != null) {
                if (legacyBlur) {
                    PlayerBackgroundMode.GAUSSIAN_BLUR
                } else {
                    PlayerBackgroundMode.GLOW_GRADIENT
                }
            } else {
                PlayerBackgroundMode.GAUSSIAN_BLUR
            }
        }
    }

    val lastSeedColorLight: Flow<Int?> = context.dataStore.data.map { it[LAST_SEED_COLOR_LIGHT] }
    val lastSeedColorDark: Flow<Int?> = context.dataStore.data.map { it[LAST_SEED_COLOR_DARK] }

    val showOfflineToast: Flow<Boolean> = context.dataStore.data.map { it[SHOW_OFFLINE_TOAST] ?: true }
    val lastCacheScanTime: Flow<Long> = context.dataStore.data.map { it[LAST_CACHE_SCAN_TIME] ?: 0L }

    suspend fun saveLastSeedColor(color: Int?, isDark: Boolean) {
        context.dataStore.edit { preferences ->
            val key = if (isDark) LAST_SEED_COLOR_DARK else LAST_SEED_COLOR_LIGHT
            if (color == null) {
                preferences.remove(key)
            } else {
                preferences[key] = color
            }
        }
    }

    suspend fun saveServerSettings(url: String, user: String, pass: String) {
        
        encryptedPrefs.edit()
            .putString(EncryptedKeys.ENC_SERVER_URL, url)
            .putString(EncryptedKeys.ENC_USERNAME, user)
            .putString(EncryptedKeys.ENC_PASSWORD, pass)
            .apply()
        
        
        encryptedCache[EncryptedKeys.ENC_SERVER_URL] = url
        encryptedCache[EncryptedKeys.ENC_USERNAME] = user
        encryptedCache[EncryptedKeys.ENC_PASSWORD] = pass

        
        context.dataStore.edit { preferences ->
            preferences.remove(SERVER_URL)
            preferences.remove(USERNAME)
            preferences.remove(PASSWORD)
            
            val currentVer = preferences[SERVER_CONFIG_VERSION] ?: 0
            preferences[SERVER_CONFIG_VERSION] = currentVer + 1
        }
    }

    suspend fun saveCacheLocation(location: String) {
        context.dataStore.edit { preferences ->
            preferences[CACHE_LOCATION] = location
        }
    }

    suspend fun saveMaxCacheSize(size: Long) {
        context.dataStore.edit { preferences ->
            preferences[MAX_CACHE_SIZE] = size
        }
    }

    suspend fun saveMobilePlayAllowed(allowed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MOBILE_PLAY_ALLOWED] = allowed
        }
    }

    suspend fun saveLogLevel(level: LogLevel) {
        context.dataStore.edit { preferences ->
            preferences[LOG_LEVEL] = level.name
        }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode.name
        }
    }

    suspend fun saveColorMode(mode: ColorMode) {
        context.dataStore.edit { preferences ->
            preferences[COLOR_MODE] = mode.name
        }
    }

    suspend fun saveSilentCacheEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SILENT_CACHE_ENABLED] = enabled
        }
    }

    suspend fun saveAutoExportSilentCache(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_EXPORT_SILENT_CACHE] = enabled
        }
    }

    suspend fun saveAutoCleanCacheAfterExport(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CLEAN_CACHE_AFTER_EXPORT] = enabled
        }
    }

    suspend fun savePlaybackState(
        songId: String?, 
        position: Long, 
        queueIndex: Int,
        queueTitle: String?,
        repeatMode: Int,
        shuffleMode: Boolean
    ) {
        context.dataStore.edit { preferences ->
            if (songId != null) {
                preferences[LAST_SONG_ID] = songId
            } else {
                preferences.remove(LAST_SONG_ID)
            }
            preferences[LAST_POSITION] = position
            preferences[LAST_QUEUE_INDEX] = queueIndex
            if (queueTitle != null) {
                preferences[LAST_QUEUE_TITLE] = queueTitle
            } else {
                preferences.remove(LAST_QUEUE_TITLE)
            }
            preferences[REPEAT_MODE] = repeatMode
            preferences[SHUFFLE_MODE] = shuffleMode
        }
    }

    suspend fun saveLastSyncTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SYNC_TIME] = time
        }
    }

    suspend fun saveLastFullSyncTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_FULL_SYNC_TIME] = time
        }
    }

    suspend fun saveServerLastModified(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_LAST_MODIFIED] = time
        }
    }

    suspend fun saveIsSyncing(syncing: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_SYNCING] = syncing
        }
    }

    suspend fun saveSyncProgress(progress: String?) {
        context.dataStore.edit { preferences ->
            if (progress == null) preferences.remove(SYNC_PROGRESS)
            else preferences[SYNC_PROGRESS] = progress
        }
    }

    suspend fun saveBackgroundSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKGROUND_SYNC_ENABLED] = enabled
        }
    }

    suspend fun saveLastSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SLEEP_TIMER_MINUTES] = minutes
        }
    }

    suspend fun saveLastSleepTimerSongCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SLEEP_TIMER_SONG_COUNT] = count
        }
    }

    suspend fun saveCarBluetoothEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CAR_BLUETOOTH_ENABLED] = enabled
        }
    }

    suspend fun saveSyncPlaybackState(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SYNC_PLAYBACK_STATE] = enabled
        }
    }

    suspend fun saveSkipSilenceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SKIP_SILENCE_ENABLED] = enabled
        }
    }

    suspend fun saveReplayGainEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REPLAY_GAIN_ENABLED] = enabled
        }
    }

    suspend fun saveBluetoothLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_LYRICS_ENABLED] = enabled
        }
    }

    suspend fun saveBluetoothLyricsHideProgressBar(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR] = enabled
        }
    }

    suspend fun saveBluetoothCarDeviceNames(names: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_CAR_DEVICE_NAMES] = names
        }
    }

    suspend fun saveNotificationReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_REMINDER_ENABLED] = enabled
        }
    }

    suspend fun saveTrustAllCertificates(trust: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TRUST_ALL_CERTIFICATES] = trust
        }
        
        setTrustAllCertificatesSync(trust)
    }

    suspend fun saveUseGaussianBlurBackground(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_GAUSSIAN_BLUR_BACKGROUND] = enabled
        }
    }

    suspend fun savePlayerBackgroundMode(mode: PlayerBackgroundMode) {
        context.dataStore.edit { preferences ->
            preferences[PLAYER_BACKGROUND_MODE] = mode.name
        }
    }

    
    fun getTrustAllCertificatesSync(): Boolean {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getBoolean("trust_all_certificates", false)
    }

    
    fun setTrustAllCertificatesSync(trust: Boolean) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("trust_all_certificates", trust).apply()
    }

    suspend fun saveShowOfflineToast(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_OFFLINE_TOAST] = show
        }
        setShowOfflineToastSync(show)
    }

    fun getShowOfflineToastSync(): Boolean {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getBoolean("show_offline_toast", true)
    }

    fun setShowOfflineToastSync(show: Boolean) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("show_offline_toast", show).apply()
    }

    suspend fun saveLastCacheScanTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CACHE_SCAN_TIME] = time
        }
    }

    suspend fun saveServerCapabilities(caps: ServerCapabilities?) {
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

    suspend fun saveLastPingTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_PING_TIME] = time
        }
        setLastPingTimeSync(time)
    }

    fun getServerCapabilitiesSync(): ServerCapabilities? {
        val jsonString = context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getString("server_capabilities_json", null)
        return try {
            jsonString?.let { Json.decodeFromString(ServerCapabilities.serializer(), it) }
        } catch (e: Exception) {
            null
        }
    }

    fun setServerCapabilitiesSync(caps: ServerCapabilities?) {
        val jsonString = caps?.let { Json.encodeToString(ServerCapabilities.serializer(), it) }
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putString("server_capabilities_json", jsonString).apply()
    }

    fun getLastPingTimeSync(): Long {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getLong("last_ping_time", 0L)
    }

    fun setLastPingTimeSync(time: Long) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putLong("last_ping_time", time).apply()
    }
}

