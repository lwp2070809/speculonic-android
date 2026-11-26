package de.lwp2070809.speculonic.network

import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface NetworkEvent {
    object ServerOffline : NetworkEvent
    object NetworkRestricted : NetworkEvent
}

enum class BlockReason {
    COOLDOWN,
    PROBE_IN_PROGRESS
}

private const val NO_PROBE_ID = 0L

sealed interface RequestGateResult {
    data class Allowed(
        val epoch: Long,
        val isProbe: Boolean,
        val probeId: Long = NO_PROBE_ID
    ) : RequestGateResult
    data class Blocked(val reason: BlockReason) : RequestGateResult
}

object ServerReachableManager {
    private const val PROBE_COOLDOWN_NS = 12_000_000_000L
    private const val PROBE_TIMEOUT_NS = 35_000_000_000L
    private const val FAILURE_WAVE_DEBOUNCE_NS = 2_000_000_000L
    private const val FAILURE_COUNT_RESET_WINDOW_NS = 60_000_000_000L
    private const val MAX_FAILURE_THRESHOLD = 3

    @Volatile
    var isManualOffline: Boolean = false

    @Volatile
    var isServerReachable: Boolean = true
        private set

    @Volatile
    var isPhysicallyConnected: Boolean = true
        private set

    private var epoch: Long = 0L
    private var probeSequence: Long = 0L
    private var failureCount: Int = 0
    private var hasFailureWave: Boolean = false
    private var lastFailureWaveNanos: Long = 0L
    private var trippedAtNanos: Long = 0L
    private var probeStartedAtNanos: Long = 0L
    private var activeProbeId: Long = NO_PROBE_ID

    private val _networkEventFlow = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 10)
    val networkEventFlow: SharedFlow<NetworkEvent> = _networkEventFlow.asSharedFlow()

    fun emitEvent(event: NetworkEvent) {
        _networkEventFlow.tryEmit(event)
    }

    @Synchronized
    fun acquireRequestPermit(): RequestGateResult {
        if (isServerReachable) {
            return RequestGateResult.Allowed(epoch = epoch, isProbe = false)
        }
        val now = System.nanoTime()
        if (now - trippedAtNanos < PROBE_COOLDOWN_NS) {
            return RequestGateResult.Blocked(BlockReason.COOLDOWN)
        }
        if (activeProbeId != NO_PROBE_ID) {
            if (now - probeStartedAtNanos < PROBE_TIMEOUT_NS) {
                return RequestGateResult.Blocked(BlockReason.PROBE_IN_PROGRESS)
            }
            LogManager.w("ServerReachableManager: Existing probe timed out, reclaiming probe slot")
        }
        val nextProbeId = ++probeSequence
        probeStartedAtNanos = now
        activeProbeId = nextProbeId
        return RequestGateResult.Allowed(epoch = epoch, isProbe = true, probeId = nextProbeId)
    }

    @Synchronized
    fun releaseProbeIfNeeded(permit: RequestGateResult.Allowed): Boolean {
        if (permit.epoch != epoch || !permit.isProbe) return false
        if (permit.probeId == NO_PROBE_ID || permit.probeId != activeProbeId) return false
        activeProbeId = NO_PROBE_ID
        return true
    }

    private fun acceptResult(permit: RequestGateResult.Allowed): Boolean =
        if (permit.isProbe) releaseProbeIfNeeded(permit) else permit.epoch == epoch && isServerReachable

    @Synchronized
    fun reportSuccess(permit: RequestGateResult.Allowed) {
        if (!acceptResult(permit)) return

        failureCount = 0
        hasFailureWave = false
        if (!isServerReachable) {
            isServerReachable = true
            LogManager.i("ServerReachableManager: REACHABLE")
        }
    }

    @Synchronized
    fun reportFailure(permit: RequestGateResult.Allowed) {
        if (!acceptResult(permit)) return

        val now = System.nanoTime()
        if (permit.isProbe) {
            trippedAtNanos = now
            failureCount = MAX_FAILURE_THRESHOLD
            LogManager.w("ServerReachableManager: Probe failed, still UNREACHABLE")
            return
        }

        // 2秒防抖窗口：将同一时间段内的密集并发失败归为单次故障波次，避免瞬间误判离线
        if (hasFailureWave && now - lastFailureWaveNanos <= FAILURE_WAVE_DEBOUNCE_NS) {
            LogManager.d("ServerReachableManager: debounce")
            return
        }
        if (hasFailureWave && now - lastFailureWaveNanos > FAILURE_COUNT_RESET_WINDOW_NS) {
            failureCount = 0
        }
        hasFailureWave = true
        lastFailureWaveNanos = now

        val current = ++failureCount
        if (current >= MAX_FAILURE_THRESHOLD) {
            isServerReachable = false
            trippedAtNanos = now
            LogManager.w("ServerReachableManager: UNREACHABLE")
            _networkEventFlow.tryEmit(NetworkEvent.ServerOffline)
        } else {
            LogManager.d("ServerReachableManager: failure count $current/$MAX_FAILURE_THRESHOLD")
        }
    }

    @Synchronized
    fun reset() {
        epoch++
        activeProbeId = NO_PROBE_ID
        failureCount = 0
        hasFailureWave = false
        lastFailureWaveNanos = 0L
        trippedAtNanos = 0L
        if (!isServerReachable) {
            isServerReachable = true
            LogManager.i("ServerReachableManager: reset")
        }
    }

    @Synchronized
    fun updatePhysicalConnectivity(isConnected: Boolean) {
        isPhysicallyConnected = isConnected
        if (!isConnected) {
            isServerReachable = false
        }
    }

    @Synchronized
    fun onPhysicalNetworkAvailable() {
        val shouldReset = !isPhysicallyConnected || !isServerReachable || failureCount > 0
        isPhysicallyConnected = true
        if (shouldReset) {
            reset()
        }
    }

    @Synchronized
    fun onPhysicalNetworkLost() {
        isPhysicallyConnected = false
        epoch++
        activeProbeId = NO_PROBE_ID
        failureCount = 0
        hasFailureWave = false
        if (isServerReachable) {
            isServerReachable = false
            LogManager.w("ServerReachableManager: lost")
        }
    }

    fun isOfflineOrUnreachable(): Boolean {
        return !isPhysicallyConnected || !isServerReachable || isManualOffline
    }
}
