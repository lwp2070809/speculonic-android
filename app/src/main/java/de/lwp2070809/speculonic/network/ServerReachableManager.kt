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
        // 2秒防抖窗口：将同一时间段内的密集并发失败（如页面加载时多个API/图片并发超时）归为单次故障波次，避免瞬间误判离线触发全网熔断
        if (now - lastFailureLogTime > 2000L) {
            // 若距离上次故障波次已超过 60 秒，重置陈旧的历史故障计数，防止跨长时间偶发错误累积误判
            if (now - lastFailureLogTime > 60_000L) {
                failureCount.set(0)
            }
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
                LogManager.d("ServerReachableManager: failure count $current/3")
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
