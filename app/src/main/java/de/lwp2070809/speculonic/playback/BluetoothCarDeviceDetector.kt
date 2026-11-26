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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BluetoothCarDeviceDetector(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onCarConnected: () -> Unit,
    private val onCarDisconnected: () -> Unit = {}
) {
    @Volatile
    var carBluetoothEnabled = false

    @Volatile
    var bluetoothCarDeviceNames: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            serviceScope.launch {
                reconcileConnectionOnNamesChanged()
            }
        }

    private val _carConnectionState = MutableStateFlow(false)
    val carConnectionState = _carConnectionState.asStateFlow()

    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var bluetoothA2dp: BluetoothA2dp? = null
    
    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                LogManager.d("BluetoothCarDeviceDetector: A2DP Profile connected.")
                checkConnectionState(notifyOnConnect = true)
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
    private var audioSettleJob: Job? = null
    private var connectionDebounceJob: Job? = null
    private val stateMutex = Mutex()

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
                    audioSettleJob?.cancel()
                    audioSettleJob = serviceScope.launch {
                        delay(AUDIO_DEVICE_SETTLE_DELAY_MS)
                        handleAudioDeviceSettle()
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

    fun release(notifyDisconnect: Boolean = true) {
        if (!isInitialized.getAndSet(false)) return
        audioSettleJob?.cancel()
        audioSettleJob = null
        connectionDebounceJob?.cancel()
        connectionDebounceJob = null
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
        val wasConnected = _carConnectionState.value
        _carConnectionState.value = false
        if (notifyDisconnect && wasConnected) {
            onCarDisconnected()
        }
    }

    private suspend fun reconcileConnectionOnNamesChanged() {
        stateMutex.withLock {
            audioSettleJob?.cancel()
            audioSettleJob = null
            connectionDebounceJob?.cancel()
            connectionDebounceJob = null
            val wasConnected = _carConnectionState.value
            val isNowConnected = if (carBluetoothEnabled && isInitialized.get()) {
                probeCarBluetoothConnected()
            } else {
                false
            }
            if (!isInitialized.get()) return
            _carConnectionState.value = isNowConnected
            if (carBluetoothEnabled && !wasConnected && isNowConnected) {
                onCarConnected()
            } else if (wasConnected && !isNowConnected) {
                onCarDisconnected()
            }
        }
    }

    private suspend fun handleAudioDeviceSettle() {
        stateMutex.withLock {
            if (!isInitialized.get()) return
            val wasConnected = _carConnectionState.value
            val connected = probeCarBluetoothConnected()
            if (!isInitialized.get()) return
            _carConnectionState.value = connected
            if (carBluetoothEnabled && connected) {
                LogManager.i("BluetoothCarDeviceDetector: Car Bluetooth audio device mounted.")
                onCarConnected()
            } else if (wasConnected && !connected) {
                onCarDisconnected()
            }
        }
    }

    fun checkConnectionState(notifyOnConnect: Boolean = false) {
        serviceScope.launch {
            stateMutex.withLock {
                if (!isInitialized.get()) return@withLock
                connectionDebounceJob?.cancel()
                val wasConnected = _carConnectionState.value
                val isConnected = probeCarBluetoothConnected()
                if (!isInitialized.get()) return@withLock
                if (isConnected) {
                    _carConnectionState.value = true
                    if (shouldNotifyColdStartConnect(notifyOnConnect, wasConnected)) {
                        onCarConnected()
                    }
                } else {
                    scheduleDisconnectDebounce()
                }
            }
        }
    }

    private fun scheduleDisconnectDebounce() {
        connectionDebounceJob = serviceScope.launch {
            delay(DISCONNECT_DEBOUNCE_MS)
            stateMutex.withLock {
                if (!isInitialized.get()) return@withLock
                val prevConnected = _carConnectionState.value
                val stillConnected = probeCarBluetoothConnected()
                if (!isInitialized.get()) return@withLock
                _carConnectionState.value = stillConnected
                if (prevConnected && !stillConnected) {
                    onCarDisconnected()
                }
            }
        }
    }

    private fun shouldNotifyColdStartConnect(notifyOnConnect: Boolean, wasConnected: Boolean): Boolean {
        if (!notifyOnConnect || wasConnected || !carBluetoothEnabled) return false
        return audioSettleJob?.isActive != true
    }

    // 热路径统一读取内存状态缓存，避免高频切歌与歌词推送触发跨进程蓝牙 Binder IPC
    fun isCarBluetoothConnected(): Boolean = _carConnectionState.value

    private suspend fun probeCarBluetoothConnected(): Boolean = withContext(Dispatchers.IO) {
        if (!carBluetoothEnabled) return@withContext false
        val a2dp = bluetoothA2dp ?: return@withContext false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasBluetoothOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any(::isSupportedBluetoothOutput)
        if (!hasBluetoothOutput) return@withContext false

        val customNames = bluetoothCarDeviceNames
        try {
            val connectedDevices = a2dp.connectedDevices.orEmpty()
            connectedDevices.any { device -> device != null && isCarAudioDevice(device, customNames) }
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

    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private fun isCarAudioDevice(
        device: android.bluetooth.BluetoothDevice,
        customNames: Set<String>
    ): Boolean {
        val name = try {
            device.name ?: ""
        } catch (_: Exception) {
            ""
        }
        if (customNames.contains(name)) return true

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
        const val DISCONNECT_DEBOUNCE_MS = 500L
        val AMBIGUOUS_AUDIO_DEVICE_CLASSES = setOf(1052, 1032)
    }
}
