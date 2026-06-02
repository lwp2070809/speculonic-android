package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class NetworkStatus(val isOnline: Boolean, val isMetered: Boolean)

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
    val isMetered: Flow<Boolean>
    val networkStatus: Flow<NetworkStatus>
}

class ConnectivityManagerNetworkMonitor(
    context: Context
) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override val isOnline: Flow<Boolean> = observeCapabilities {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override val isMetered: Flow<Boolean> = observeCapabilities {
        !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun observeCapabilities(predicate: (NetworkCapabilities) -> Boolean): Flow<Boolean> = callbackFlow {
        if (connectivityManager == null) {
            channel.trySend(false)
            channel.close()
            return@callbackFlow
        }

        var debounceJob: kotlinx.coroutines.Job? = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                debounceJob?.cancel()
                channel.trySend(predicate(capabilities))
            }

            override fun onLost(network: Network) {
                
                val isConnected = connectivityManager.isCurrentlyConnected(predicate)
                if (!isConnected) {
                    
                    debounceJob?.cancel()
                    debounceJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        kotlinx.coroutines.delay(1500)
                        channel.trySend(false)
                    }
                } else {
                    channel.trySend(true)
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        channel.trySend(connectivityManager.isCurrentlyConnected(predicate))

        awaitClose {
            debounceJob?.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
    .distinctUntilChanged()

    private fun ConnectivityManager.isCurrentlyConnected(predicate: (NetworkCapabilities) -> Boolean): Boolean {
        return activeNetwork?.let(::getNetworkCapabilities)?.let(predicate) ?: false
    }

    override val networkStatus: Flow<NetworkStatus> = callbackFlow {
        if (connectivityManager == null) {
            channel.trySend(NetworkStatus(false, false))
            channel.close()
            return@callbackFlow
        }

        var debounceJob: kotlinx.coroutines.Job? = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                debounceJob?.cancel()
                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                channel.trySend(NetworkStatus(online, metered))
            }

            override fun onLost(network: Network) {
                val activeNetwork = connectivityManager.activeNetwork
                val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
                val metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false

                if (!online) {
                    debounceJob?.cancel()
                    debounceJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        kotlinx.coroutines.delay(1500)
                        channel.trySend(NetworkStatus(false, false))
                    }
                } else {
                    channel.trySend(NetworkStatus(true, metered))
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        val metered = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: false
        channel.trySend(NetworkStatus(online, metered))

        awaitClose {
            debounceJob?.cancel()
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
    .distinctUntilChanged()
}
