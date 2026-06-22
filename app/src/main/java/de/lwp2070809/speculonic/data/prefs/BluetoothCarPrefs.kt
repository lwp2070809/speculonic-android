package de.lwp2070809.speculonic.data.prefs

import kotlinx.coroutines.flow.Flow

interface BluetoothCarPrefs {
    val carBluetoothEnabled: Flow<Boolean>
    val bluetoothLyricsEnabled: Flow<Boolean>
    val bluetoothLyricsHideProgressBar: Flow<Boolean>
    val bluetoothCarDeviceNames: Flow<Set<String>>

    suspend fun saveCarBluetoothEnabled(enabled: Boolean)
    suspend fun saveBluetoothLyricsEnabled(enabled: Boolean)
    suspend fun saveBluetoothLyricsHideProgressBar(enabled: Boolean)
    suspend fun saveBluetoothCarDeviceNames(names: Set<String>)
}
