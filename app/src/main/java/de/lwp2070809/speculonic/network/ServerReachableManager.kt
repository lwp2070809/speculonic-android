package de.lwp2070809.speculonic.network

import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

sealed interface NetworkEvent {
    object ServerOffline : NetworkEvent
    object NetworkRestricted : NetworkEvent
}

object ServerReachableManager {
    @Volatile
    var isManualOffline: Boolean = false

    @Volatile
    var isServerReachable: Boolean = true
        private set

    @Volatile
    var isPhysicallyConnected: Boolean = true
        internal set

    @Volatile
    var lastFailureTimestamp: Long = 0L
        private set

    @Volatile
    private var lastFailureLogTime: Long = 0L

    private val failureCount = AtomicInteger(0)

    private val _networkEventFlow = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 10)
    val networkEventFlow: SharedFlow<NetworkEvent> = _networkEventFlow.asSharedFlow()

    fun emitEvent(event: NetworkEvent) {
        _networkEventFlow.tryEmit(event)
    }

    @Synchronized
    fun handleSuccess() {
        failureCount.set(0)
        if (!isServerReachable) {
            isServerReachable = true
            LogManager.i("ServerReachableManager: REACHABLE")
        }
    }

    @Synchronized
    fun handleFailure() {
        val now = System.currentTimeMillis()
        if (now - lastFailureLogTime > 2000L) {
            lastFailureLogTime = now
            val current = failureCount.incrementAndGet()
            if (current >= 3) {
                if (isServerReachable) {
                    isServerReachable = false
                    lastFailureTimestamp = now
                    LogManager.w("ServerReachableManager: UNREACHABLE")
                    _networkEventFlow.tryEmit(NetworkEvent.ServerOffline)
                }
            } else {
                LogManager.d("ServerReachableManager: $current/3")
            }
        } else {
            LogManager.d("ServerReachableManager: debounce")
        }
    }

    @Synchronized
    fun reset() {
        failureCount.set(0)
        if (!isServerReachable) {
            isServerReachable = true
            LogManager.i("ServerReachableManager: reset")
        }
    }

    @Synchronized
    fun handleNetworkLost() {
        failureCount.set(0)
        if (isServerReachable) {
            isServerReachable = false
            LogManager.w("ServerReachableManager: lost")
        }
    }

    fun isOfflineOrUnreachable(): Boolean {
        return !isPhysicallyConnected || !isServerReachable || isManualOffline
    }
}
