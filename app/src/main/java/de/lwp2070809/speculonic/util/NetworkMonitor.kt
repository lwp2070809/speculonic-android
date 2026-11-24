package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetworkStatus(val isOnline: Boolean, val isMetered: Boolean)

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
    val isMetered: Flow<Boolean>
    val networkStatus: StateFlow<NetworkStatus>
    val currentNetworkStatus: NetworkStatus
}

class ConnectivityManagerNetworkMonitor(
    context: Context,
    scope: CoroutineScope
) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var isCallbackActive = false

    private fun readCurrentNetworkStatus(losingNetwork: Network? = null): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus(isOnline = false, isMetered = false)
        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null || activeNetwork == losingNetwork) {
            return NetworkStatus(isOnline = false, isMetered = false)
        }
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val metered = online && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkStatus(isOnline = online, isMetered = metered)
    }

    // 通过 singleton 作用域共享单一系统 NetworkCallback，避免多订阅方重复注册触发系统配额限制
    override val networkStatus: StateFlow<NetworkStatus> = callbackFlow {
        if (connectivityManager == null) {
            channel.trySend(NetworkStatus(false, false))
            channel.close()
            return@callbackFlow
        }

        var debounceJob: Job? = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (online) {
                    debounceJob?.cancel()
                    channel.trySend(NetworkStatus(true, metered))
                } else {
                    debounceJob?.cancel()
                    debounceJob = launch {
                        delay(NETWORK_LOST_RECHECK_DELAY_MS)
                        channel.trySend(readCurrentNetworkStatus())
                    }
                }
            }

            override fun onLost(network: Network) {
                val status = readCurrentNetworkStatus(losingNetwork = network)
                debounceJob?.cancel()
                if (status.isOnline) {
                    channel.trySend(status)
                }
                debounceJob = launch {
                    delay(NETWORK_LOST_RECHECK_DELAY_MS)
                    channel.trySend(readCurrentNetworkStatus())
                }
            }
        }

        channel.trySend(readCurrentNetworkStatus())

        connectivityManager.registerDefaultNetworkCallback(callback)
        isCallbackActive = true

        awaitClose {
            isCallbackActive = false
            debounceJob?.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
    .distinctUntilChanged()
    .onEach { status ->
        val typeStr = if (status.isMetered) "Metered (Cellular)" else "Unmetered (Wi-Fi/LAN)"
        LogManager.i(LogTag.NETWORK, "Network state changed -> Online: ${status.isOnline}, Type: $typeStr")
    }
    .stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = readCurrentNetworkStatus()
    )

    override val currentNetworkStatus: NetworkStatus
        get() = if (isCallbackActive) {
            networkStatus.value
        } else {
            readCurrentNetworkStatus()
        }

    override val isOnline: Flow<Boolean> = networkStatus.map { it.isOnline }.distinctUntilChanged()

    override val isMetered: Flow<Boolean> = networkStatus.map { it.isMetered }.distinctUntilChanged()

    private companion object {
        const val NETWORK_LOST_RECHECK_DELAY_MS = 1500L
    }
}
