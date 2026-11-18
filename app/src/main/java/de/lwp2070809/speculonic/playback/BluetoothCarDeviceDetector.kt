@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioManager
import androidx.core.content.ContextCompat
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

    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)
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
    private val debounceMutex = kotlinx.coroutines.sync.Mutex()

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun init() {
        if (!hasBluetoothConnectPermission()) {
            LogManager.w("BluetoothCarDeviceDetector: Missing BLUETOOTH_CONNECT permission, skipping initialization.")
            return
        }
        if (isInitialized.getAndSet(true)) return
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter?.getProfileProxy(context, bluetoothProfileListener, BluetoothProfile.A2DP)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    checkConnectionState()
                    val hasBluetoothAdded = addedDevices?.any(::isSupportedBluetoothOutput) == true
                    if (!hasBluetoothAdded) return
                    serviceScope.launch {
                        delay(AUDIO_DEVICE_SETTLE_DELAY_MS)
                        val connected = probeCarBluetoothConnected()
                        _carConnectionState.value = connected
                        if (carBluetoothEnabled && connected) {
                            LogManager.i("BluetoothCarDeviceDetector: Car Bluetooth audio device mounted.")
                            onCarConnected()
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                    checkConnectionState()
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: SecurityException) {
            LogManager.w("BluetoothCarDeviceDetector: init missing permission", e)
        } catch (e: Exception) {
            LogManager.e("BluetoothCarDeviceDetector: Failed to init", e)
        }
    }

    fun release() {
        if (!isInitialized.get()) return
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
        } catch (_: Exception) {
            
        }
        bluetoothA2dp = null
        connectionDebounceJob?.cancel()
        connectionDebounceJob = null
        _carConnectionState.value = false
        isInitialized.set(false)
    }

    fun checkConnectionState() {
        serviceScope.launch {
            debounceMutex.lock()
            try {
                connectionDebounceJob?.cancel()
                val isConnected = probeCarBluetoothConnected()
                if (isConnected) {
                    _carConnectionState.value = true
                } else {
                    connectionDebounceJob = serviceScope.launch {
                        delay(500)
                        _carConnectionState.value = probeCarBluetoothConnected()
                    }
                }
            } finally {
                debounceMutex.unlock()
            }
        }
    }

    // 热路径统一读取内存状态缓存，避免高频切歌与歌词推送触发跨进程蓝牙 Binder IPC
    fun isCarBluetoothConnected(): Boolean = _carConnectionState.value

    private fun probeCarBluetoothConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasBluetoothOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any(::isSupportedBluetoothOutput)
        if (!hasBluetoothOutput) return false

        return try {
            val connectedDevices = bluetoothA2dp?.connectedDevices.orEmpty()
            connectedDevices.any { device -> device != null && isCarAudioDevice(device) }
        } catch (_: SecurityException) {
            LogManager.w(
                "BluetoothCarDeviceDetector: SecurityException - Missing " +
                    "android.Manifest.permission.BLUETOOTH_CONNECT permission, car Bluetooth features will be disabled."
            )
            false
        } catch (e: Exception) {
            LogManager.e("BluetoothCarDeviceDetector: Error identifying bluetooth device", e)
            false
        }
    }

    private fun isSupportedBluetoothOutput(device: android.media.AudioDeviceInfo): Boolean {
        return device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    private fun isCarAudioDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        val name = try {
            device.name ?: ""
        } catch (_: Exception) {
            ""
        }
        if (bluetoothCarDeviceNames.contains(name)) return true

        val deviceClass = device.bluetoothClass
        val classInt = deviceClass?.deviceClass ?: -1
        if (classInt == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) return true

        val majorClassInt = deviceClass?.majorDeviceClass ?: -1
        return majorClassInt == BluetoothClass.Device.Major.AUDIO_VIDEO &&
            classInt in AMBIGUOUS_AUDIO_DEVICE_CLASSES &&
            BluetoothConstants.matchesCarKeyword(name.lowercase())
    }

    private companion object {
        const val AUDIO_DEVICE_SETTLE_DELAY_MS = 1500L
        val AMBIGUOUS_AUDIO_DEVICE_CLASSES = setOf(1052, 1032)
    }
}
