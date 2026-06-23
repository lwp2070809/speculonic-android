package de.lwp2070809.speculonic.data.prefs

import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.data.UpdateCheckInterval
import de.lwp2070809.speculonic.util.LogLevel
import kotlinx.coroutines.flow.Flow

interface GeneralSystemPrefs {
    val logLevel: Flow<LogLevel>
    val themeMode: Flow<ThemeMode>
    val colorMode: Flow<ColorMode>
    val playerBackgroundMode: Flow<PlayerBackgroundMode>
    val lastSeedColorLight: Flow<Int?>
    val lastSeedColorDark: Flow<Int?>
    val showOfflineToast: Flow<Boolean>
    val updateCheckInterval: Flow<UpdateCheckInterval>
    val lastUpdateCheckTime: Flow<Long>

    suspend fun saveLogLevel(level: LogLevel)
    suspend fun saveThemeMode(mode: ThemeMode)
    suspend fun saveColorMode(mode: ColorMode)
    suspend fun savePlayerBackgroundMode(mode: PlayerBackgroundMode)
    suspend fun saveLastSeedColor(color: Int?, isDark: Boolean)
    suspend fun saveShowOfflineToast(show: Boolean)
    suspend fun saveUpdateCheckInterval(interval: UpdateCheckInterval)
    suspend fun saveLastUpdateCheckTime(time: Long)
}
