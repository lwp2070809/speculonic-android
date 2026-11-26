package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class NetworkStatus(val isOnline: Boolean, val isMetered: Boolean)

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
    val isMetered: Flow<Boolean>
    val physicalOnline: StateFlow<Boolean>
    val networkStatus: StateFlow<NetworkStatus>
    val currentNetworkStatus: NetworkStatus
    val networkRouteChanged: Flow<Unit>
}

class ConnectivityManagerNetworkMonitor(
    context: Context,
    scope: CoroutineScope
) : NetworkMonitor {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var isCallbackActive = false

    private val _physicalOnline = MutableStateFlow(readCurrentNetworkStatus().isOnline)
    override val physicalOnline: StateFlow<Boolean> = _physicalOnline.asStateFlow()

    private val _networkRouteChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val networkRouteChanged: Flow<Unit> = _networkRouteChanged.asSharedFlow()

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

    private fun buildLinkSignature(linkProperties: LinkProperties): String {
        val addresses = linkProperties.linkAddresses
            .mapNotNull { it.address?.hostAddress }
            .sorted()
            .joinToString(",")
        val dnsServers = linkProperties.dnsServers
            .mapNotNull { it.hostAddress }
            .sorted()
            .joinToString(",")
        return "$addresses|$dnsServers"
    }

    // 通过 singleton 作用域共享单一系统 NetworkCallback，避免多订阅方重复注册触发系统配额限制
    override val networkStatus: StateFlow<NetworkStatus> = callbackFlow {
        if (connectivityManager == null) {
            _physicalOnline.value = false
            channel.trySend(NetworkStatus(false, false))
            channel.close()
            return@callbackFlow
        }

        var debounceJob: Job? = null
        val initialStatus = readCurrentNetworkStatus()
        _physicalOnline.value = initialStatus.isOnline
        val initialNetwork = connectivityManager.activeNetwork
        var lastNetworkHandle: Long? = initialNetwork?.networkHandle
        var lastVpnState: Boolean? = initialNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        var lastLinkSignature: String? = initialNetwork
            ?.let { connectivityManager.getLinkProperties(it) }
            ?.let(::buildLinkSignature)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                if (lastNetworkHandle != handle) {
                    if (lastNetworkHandle != null && _physicalOnline.value) {
                        _networkRouteChanged.tryEmit(Unit)
                    }
                    lastNetworkHandle = handle
                    // 新网络句柄即将触发 onCapabilitiesChanged/onLinkPropertiesChanged，清空旧特征避免重复发射路由变更
                    lastVpnState = null
                    lastLinkSignature = null
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val handle = network.networkHandle
                val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val handleChanged = lastNetworkHandle != null && lastNetworkHandle != handle
                val vpnToggled = lastVpnState != null && lastVpnState != isVpn
                if (_physicalOnline.value && (handleChanged || vpnToggled)) {
                    _networkRouteChanged.tryEmit(Unit)
                }
                lastNetworkHandle = handle
                lastVpnState = isVpn

                val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                _physicalOnline.value = online
                if (online) {
                    debounceJob?.cancel()
                    channel.trySend(NetworkStatus(true, metered))
                } else {
                    debounceJob?.cancel()
                    debounceJob = launch {
                        delay(NETWORK_LOST_RECHECK_DELAY_MS.milliseconds)
                        val recheckStatus = readCurrentNetworkStatus()
                        _physicalOnline.value = recheckStatus.isOnline
                        channel.trySend(recheckStatus)
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network.networkHandle != lastNetworkHandle) return
                val signature = buildLinkSignature(linkProperties)
                if (lastLinkSignature != null && lastLinkSignature != signature) {
                    _networkRouteChanged.tryEmit(Unit)
                }
                lastLinkSignature = signature
            }

            override fun onLost(network: Network) {
                val status = readCurrentNetworkStatus(losingNetwork = network)
                _physicalOnline.value = status.isOnline
                debounceJob?.cancel()
                if (status.isOnline) {
                    channel.trySend(status)
                }
                debounceJob = launch {
                    delay(NETWORK_LOST_RECHECK_DELAY_MS.milliseconds)
                    val recheckStatus = readCurrentNetworkStatus()
                    _physicalOnline.value = recheckStatus.isOnline
                    channel.trySend(recheckStatus)
                }
            }
        }

        channel.trySend(initialStatus)

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
        started = SharingStarted.Eagerly,
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
