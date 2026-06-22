package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.data.UpdateCheckInterval
import de.lwp2070809.speculonic.data.dataStore
import de.lwp2070809.speculonic.util.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GeneralSystemPrefsImpl(private val context: Context) : GeneralSystemPrefs {

    companion object {
        private val LOG_LEVEL = stringPreferencesKey("log_level")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val COLOR_MODE = stringPreferencesKey("color_mode")
        private val NOTIFICATION_REMINDER_ENABLED = booleanPreferencesKey("notification_reminder_enabled")
        private val USE_GAUSSIAN_BLUR_BACKGROUND = booleanPreferencesKey("use_gaussian_blur_background")
        private val PLAYER_BACKGROUND_MODE = stringPreferencesKey("player_background_mode")
        private val LAST_SEED_COLOR_LIGHT = intPreferencesKey("last_seed_color_light")
        private val LAST_SEED_COLOR_DARK = intPreferencesKey("last_seed_color_dark")
        private val SHOW_OFFLINE_TOAST = booleanPreferencesKey("show_offline_toast")
        private val UPDATE_CHECK_INTERVAL = stringPreferencesKey("update_check_interval")
        private val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
    }

    override val logLevel: Flow<LogLevel> = context.dataStore.data.map { preferences ->
        val levelName = preferences[LOG_LEVEL] ?: LogLevel.INFO.name
        try {
            LogLevel.valueOf(levelName)
        } catch (e: Exception) {
            LogLevel.INFO
        }
    }

    override val themeMode: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[THEME_MODE] ?: ThemeMode.SYSTEM.name
        try {
            ThemeMode.valueOf(modeName)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    override val colorMode: Flow<ColorMode> = context.dataStore.data.map { preferences ->
        val modeName = preferences[COLOR_MODE] ?: ColorMode.ALBUM_COVER.name
        try {
            ColorMode.valueOf(modeName)
        } catch (e: Exception) {
            ColorMode.ALBUM_COVER
        }
    }

    override val notificationReminderEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATION_REMINDER_ENABLED] ?: true }
    override val useGaussianBlurBackground: Flow<Boolean> = context.dataStore.data.map { it[USE_GAUSSIAN_BLUR_BACKGROUND] ?: false }
    
    override val playerBackgroundMode: Flow<PlayerBackgroundMode> = context.dataStore.data.map { preferences ->
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

    override val lastSeedColorLight: Flow<Int?> = context.dataStore.data.map { it[LAST_SEED_COLOR_LIGHT] }
    override val lastSeedColorDark: Flow<Int?> = context.dataStore.data.map { it[LAST_SEED_COLOR_DARK] }
    override val showOfflineToast: Flow<Boolean> = context.dataStore.data.map { it[SHOW_OFFLINE_TOAST] ?: true }

    override val updateCheckInterval: Flow<UpdateCheckInterval> = context.dataStore.data.map { preferences ->
        val defaultInterval = if (de.lwp2070809.speculonic.BuildConfig.UPDATE_CHECK_ENABLED) {
            UpdateCheckInterval.WEEKLY
        } else {
            UpdateCheckInterval.DISABLED
        }
        val intervalName = preferences[UPDATE_CHECK_INTERVAL] ?: defaultInterval.name
        try {
            UpdateCheckInterval.valueOf(intervalName)
        } catch (e: Exception) {
            defaultInterval
        }
    }

    override val lastUpdateCheckTime: Flow<Long> = context.dataStore.data.map { it[LAST_UPDATE_CHECK_TIME] ?: 0L }

    override suspend fun saveLogLevel(level: LogLevel) {
        context.dataStore.edit { preferences ->
            preferences[LOG_LEVEL] = level.name
        }
    }

    override suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode.name
        }
    }

    override suspend fun saveColorMode(mode: ColorMode) {
        context.dataStore.edit { preferences ->
            preferences[COLOR_MODE] = mode.name
        }
    }

    override suspend fun saveNotificationReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_REMINDER_ENABLED] = enabled
        }
    }

    override suspend fun saveUseGaussianBlurBackground(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_GAUSSIAN_BLUR_BACKGROUND] = enabled
        }
    }

    override suspend fun savePlayerBackgroundMode(mode: PlayerBackgroundMode) {
        context.dataStore.edit { preferences ->
            preferences[PLAYER_BACKGROUND_MODE] = mode.name
        }
    }

    override suspend fun saveLastSeedColor(color: Int?, isDark: Boolean) {
        context.dataStore.edit { preferences ->
            val key = if (isDark) LAST_SEED_COLOR_DARK else LAST_SEED_COLOR_LIGHT
            if (color == null) {
                preferences.remove(key)
            } else {
                preferences[key] = color
            }
        }
    }

    override suspend fun saveShowOfflineToast(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_OFFLINE_TOAST] = show
        }
        setShowOfflineToastSync(show)
    }

    override fun getShowOfflineToastSync(): Boolean {
        return context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .getBoolean("show_offline_toast", true)
    }

    override fun setShowOfflineToastSync(show: Boolean) {
        context.getSharedPreferences("speculonic_network_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("show_offline_toast", show).apply()
    }

    override suspend fun saveUpdateCheckInterval(interval: UpdateCheckInterval) {
        context.dataStore.edit { preferences ->
            preferences[UPDATE_CHECK_INTERVAL] = interval.name
        }
    }

    override suspend fun saveLastUpdateCheckTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_UPDATE_CHECK_TIME] = time
        }
    }
}
