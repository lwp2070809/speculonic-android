package de.lwp2070809.speculonic.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.di.NetworkModule
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
import de.lwp2070809.speculonic.util.NetworkMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用全局运行态协调器：托管与 Activity/UI 无关的日志分级同步、离线标志同步、
 * 计费网络自动离线策略、转码偏好缓存预热与卡死同步恢复。
 */
@Singleton
class AppRuntimeCoordinator(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val networkMonitor: NetworkMonitor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val triggerMetadataSync: (Boolean) -> Unit = { forceRefresh ->
        MetadataSyncWorker.runOnce(context, forceRefresh = forceRefresh)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val started = AtomicBoolean(false)
    private val reconciledOnLaunch = AtomicBoolean(false)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        preferencesManager: PreferencesManager,
        networkMonitor: NetworkMonitor
    ) : this(
        context = context,
        preferencesManager = preferencesManager,
        networkMonitor = networkMonitor,
        ioDispatcher = Dispatchers.IO
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            preferencesManager.logLevel.collectLatest { level ->
                LogManager.setMinLevel(level)
            }
        }

        scope.launch {
            preferencesManager.offlineModeEnabled.collectLatest { offline ->
                NetworkModule.ServerReachableManager.isManualOffline = offline
                LogManager.i(LogTag.APP, "AppRuntimeCoordinator: Offline mode state updated -> $offline")
            }
        }

        scope.launch {
            combine(
                networkMonitor.networkStatus.map { it.isMetered }.distinctUntilChanged(),
                preferencesManager.autoOfflineOnMetered
            ) { isMetered, autoOffline ->
                isMetered to autoOffline
            }
                .distinctUntilChanged()
                .collect { (isMetered, autoOffline) ->
                    if (autoOffline) {
                        LogManager.i(
                            LogTag.APP,
                            "AppRuntimeCoordinator: Auto-offline triggered (isMetered=$isMetered) -> offlineMode=$isMetered"
                        )
                        preferencesManager.saveOfflineModeEnabled(isMetered)
                    }
                }
        }

        scope.launch {
            // 预热转码偏好本地镜像，保证进入页面首帧即可同步取得真实设置值
            preferencesManager.transcodeIncompatibleFormats.first()
        }
    }

    fun reconcileStuckSyncOnAppLaunch() {
        if (!reconciledOnLaunch.compareAndSet(false, true)) return
        scope.launch {
            reconcileStuckSync()
        }
    }

    internal suspend fun reconcileStuckSync() {
        val wasSyncing = preferencesManager.isSyncing.first()
        if (!wasSyncing) return

        val lastSyncTime = preferencesManager.lastSyncTime.first()
        if (nowProvider() - lastSyncTime > STUCK_SYNC_TIMEOUT_MS) {
            LogManager.w("AppRuntimeCoordinator: Detected stuck isSyncing state (>30m). Resetting to false.")
            preferencesManager.saveIsSyncing(false)
            triggerMetadataSync(false)
        } else {
            LogManager.w("AppRuntimeCoordinator: Detected interrupted sync. Triggering re-sync...")
            triggerMetadataSync(true)
        }
    }

    companion object {
        private const val STUCK_SYNC_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
