package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.lwp2070809.speculonic.data.prefs.BluetoothCarPrefs
import de.lwp2070809.speculonic.data.prefs.BluetoothCarPrefsImpl
import de.lwp2070809.speculonic.data.prefs.CacheSyncPrefs
import de.lwp2070809.speculonic.data.prefs.CacheSyncPrefsImpl
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefs
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefsImpl
import de.lwp2070809.speculonic.data.prefs.GeneralSystemPrefs
import de.lwp2070809.speculonic.data.prefs.GeneralSystemPrefsImpl
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefs
import de.lwp2070809.speculonic.data.prefs.PlaybackPrefsImpl

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

enum class UpdateCheckInterval {
    STARTUP, DAILY, WEEKLY, DISABLED
}

class PreferencesManager(
    connectionPrefs: ConnectionPrefs,
    playbackPrefs: PlaybackPrefs,
    bluetoothCarPrefs: BluetoothCarPrefs,
    cacheSyncPrefs: CacheSyncPrefs,
    generalSystemPrefs: GeneralSystemPrefs
) : ConnectionPrefs by connectionPrefs,
    PlaybackPrefs by playbackPrefs,
    BluetoothCarPrefs by bluetoothCarPrefs,
    CacheSyncPrefs by cacheSyncPrefs,
    GeneralSystemPrefs by generalSystemPrefs {

    companion object {
        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(
                    ConnectionPrefsImpl(context.applicationContext),
                    PlaybackPrefsImpl(context.applicationContext),
                    BluetoothCarPrefsImpl(context.applicationContext),
                    CacheSyncPrefsImpl(context.applicationContext),
                    GeneralSystemPrefsImpl(context.applicationContext)
                ).also { instance = it }
            }
        }
    }
}
