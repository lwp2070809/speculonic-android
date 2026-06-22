@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioManager
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class BluetoothCarDeviceDetector(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onCarConnected: () -> Unit
) {
    var carBluetoothEnabled = false
    var bluetoothCarDeviceNames: Set<String> = emptySet()

    private val _carConnectionState = MutableStateFlow(false)
    val carConnectionState = _carConnectionState.asStateFlow()

    private var isInitialized = false
    private var bluetoothA2dp: BluetoothA2dp? = null
    
    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                LogManager.d("BluetoothCarDeviceDetector: A2DP Profile connected.")
                checkConnectionState()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
                LogManager.d("BluetoothCarDeviceDetector: A2DP Profile disconnected.")
                checkConnectionState()
            }
        }
    }

    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var connectionDebounceJob: Job? = null

    private fun hasBluetoothConnectPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun init() {
        if (isInitialized) return
        if (!hasBluetoothConnectPermission()) {
            LogManager.w("BluetoothCarDeviceDetector: 缺少 BLUETOOTH_CONNECT 权限，跳过初始化。")
            return
        }
        isInitialized = true
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.A2DP)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    checkConnectionState()
                    addedDevices?.forEach { device ->
                        if (device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || 
                            device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
                        ) {
                            serviceScope.launch {
                                delay(1500)
                                if (carBluetoothEnabled && isCarBluetoothConnected()) {
                                    LogManager.i("BluetoothCarDeviceDetector: Car Bluetooth audio device mounted.")
                                    onCarConnected()
                                }
                            }
                            return
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    checkConnectionState()
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: SecurityException) {
            LogManager.w("BluetoothCarDeviceDetector: init 缺少权限", e)
        } catch (e: Exception) {
            LogManager.e("BluetoothCarDeviceDetector: Failed to init", e)
        }
    }

    fun release() {
        if (!isInitialized) return
        try {
            audioDeviceCallback?.let {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.unregisterAudioDeviceCallback(it)
                audioDeviceCallback = null
            }
            if (hasBluetoothConnectPermission()) {
                val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothManager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
            }
        } catch (e: Exception) {
            
        }
        bluetoothA2dp = null
        connectionDebounceJob?.cancel()
        isInitialized = false
    }

    fun checkConnectionState() {
        connectionDebounceJob?.cancel()
        val isConnected = isCarBluetoothConnected()
        if (isConnected) {
            _carConnectionState.value = true
        } else {
            connectionDebounceJob = serviceScope.launch {
                delay(500)
                _carConnectionState.value = isCarBluetoothConnected()
            }
        }
    }

    
    fun isCarBluetoothConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetoothOutput = devices.any { 
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || 
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        
        if (!hasBluetoothOutput) return false
        
        return try {
            val connectedDevices = bluetoothA2dp?.connectedDevices ?: emptyList()
            if (connectedDevices.isEmpty()) {
                false
            } else {
                var foundCar = false
                connectedDevices.forEach { device ->
                    if (device == null) return@forEach
                    val name = try { device.name ?: "" } catch (e: Exception) { "" }
                    
                    if (bluetoothCarDeviceNames.contains(name)) {
                        foundCar = true
                        return@forEach
                    }

                    val deviceClass = device.bluetoothClass
                    val classInt = deviceClass?.deviceClass ?: -1
                    val majorClassInt = deviceClass?.majorDeviceClass ?: -1
                    
                    if (classInt == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) {
                        foundCar = true
                    } else if (majorClassInt == BluetoothClass.Device.Major.AUDIO_VIDEO && 
                        (classInt == 1052 || classInt == 1032)) {
                        val lowerName = name.lowercase()
                        if (BluetoothConstants.CAR_KEYWORDS.any { lowerName.contains(it) }) {
                            foundCar = true
                        }
                    }
                }
                foundCar
            }
        } catch (e: SecurityException) {
            LogManager.w("BluetoothCarDeviceDetector: SecurityException - 缺少 android.Manifest.permission.BLUETOOTH_CONNECT 权限，车机蓝牙音频功能将失效。")
            false
        } catch (e: Exception) {
            LogManager.e("BluetoothCarDeviceDetector: Error identifying bluetooth device", e)
            false
        }
    }
}
