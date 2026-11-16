package de.lwp2070809.speculonic.network

import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface NetworkEvent {
    object ServerOffline : NetworkEvent
    object NetworkRestricted : NetworkEvent
}

private data class ProbeLock(
    val token: Long,
    val acquiredAtNanos: Long
)

object ServerReachableManager {
    private const val PROBE_LOCK_TIMEOUT_NS = 35_000_000_000L

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
    private val probeSequence = AtomicLong(0L)
    private val probeLock = AtomicReference<ProbeLock?>(null)

    fun tryAcquireProbe(): Long? {
        while (true) {
            val current = probeLock.get()
            val nowNanos = System.nanoTime()
            if (current != null && (nowNanos - current.acquiredAtNanos) < PROBE_LOCK_TIMEOUT_NS) {
                return null
            }
            if (current != null) {
                LogManager.w("ServerReachableManager: Existing probe lock expired, reclaiming lock safely")
            }
            val next = ProbeLock(token = probeSequence.incrementAndGet(), acquiredAtNanos = nowNanos)
            if (probeLock.compareAndSet(current, next)) {
                return next.token
            }
        }
    }

    fun releaseProbe(token: Long) {
        while (true) {
            val current = probeLock.get() ?: return
            if (current.token != token) return
            if (probeLock.compareAndSet(current, null)) return
        }
    }

    private val _networkEventFlow = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 10)
    val networkEventFlow: SharedFlow<NetworkEvent> = _networkEventFlow.asSharedFlow()

    fun emitEvent(event: NetworkEvent) {
        _networkEventFlow.tryEmit(event)
    }

    @Synchronized
    fun handleSuccess() {
        probeLock.set(null)
        failureCount.set(0)
        if (!isServerReachable) {
            isServerReachable = true
            LogManager.i("ServerReachableManager: REACHABLE")
        }
    }

    @Synchronized
    fun handleFailure() {
        val now = System.currentTimeMillis()
        if (!isServerReachable) {
            // 当前处于熔断离线状态，半开探测再次失败：立即刷新冷却时间戳，保持熔断状态，重置探测标记
            lastFailureTimestamp = now
            failureCount.set(3)
            probeLock.set(null)
            LogManager.w("ServerReachableManager: Probe failed, still UNREACHABLE, updated lastFailureTimestamp")
            return
        }

        probeLock.set(null)
        // 2秒防抖窗口：将同一时间段内的密集并发失败（如页面加载时多个API/图片并发超时）归为单次故障波次，避免瞬间误判离线触发全网熔断
        if (now - lastFailureLogTime > 2000L) {
            // 若距离上次故障波次已超过 60 秒，重置陈旧的历史故障计数，防止跨长时间偶发错误累积误判
            if (now - lastFailureLogTime > 60_000L) {
                failureCount.set(0)
            }
            lastFailureLogTime = now
            val current = failureCount.incrementAndGet()
            if (current >= 3) {
                val wasReachable = isServerReachable
                isServerReachable = false
                lastFailureTimestamp = now
                if (wasReachable) {
                    LogManager.w("ServerReachableManager: UNREACHABLE")
                    _networkEventFlow.tryEmit(NetworkEvent.ServerOffline)
                } else {
                    LogManager.w("ServerReachableManager: still UNREACHABLE, updated lastFailureTimestamp")
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
        probeLock.set(null)
        failureCount.set(0)
        lastFailureTimestamp = 0L
        lastFailureLogTime = 0L
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
