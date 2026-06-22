package de.lwp2070809.speculonic.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import de.lwp2070809.speculonic.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BluetoothCarPrefsImpl(private val context: Context) : BluetoothCarPrefs {

    companion object {
        private val CAR_BLUETOOTH_ENABLED = booleanPreferencesKey("car_bluetooth_enabled")
        private val BLUETOOTH_LYRICS_ENABLED = booleanPreferencesKey("bluetooth_lyrics_enabled")
        private val BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR = booleanPreferencesKey("bluetooth_lyrics_hide_progress_bar")
        private val BLUETOOTH_CAR_DEVICE_NAMES = androidx.datastore.preferences.core.stringSetPreferencesKey("bluetooth_car_device_names")
    }

    override val carBluetoothEnabled: Flow<Boolean> = context.dataStore.data.map { it[CAR_BLUETOOTH_ENABLED] ?: false }
    override val bluetoothLyricsEnabled: Flow<Boolean> = context.dataStore.data.map { it[BLUETOOTH_LYRICS_ENABLED] ?: false }
    override val bluetoothLyricsHideProgressBar: Flow<Boolean> = context.dataStore.data.map { it[BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR] ?: false }
    override val bluetoothCarDeviceNames: Flow<Set<String>> = context.dataStore.data.map { it[BLUETOOTH_CAR_DEVICE_NAMES] ?: emptySet() }

    override suspend fun saveCarBluetoothEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CAR_BLUETOOTH_ENABLED] = enabled
        }
    }

    override suspend fun saveBluetoothLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_LYRICS_ENABLED] = enabled
        }
    }

    override suspend fun saveBluetoothLyricsHideProgressBar(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_LYRICS_HIDE_PROGRESS_BAR] = enabled
        }
    }

    override suspend fun saveBluetoothCarDeviceNames(names: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[BLUETOOTH_CAR_DEVICE_NAMES] = names
        }
    }
}
