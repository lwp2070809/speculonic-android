package de.lwp2070809.speculonic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.MainActivity
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import org.json.JSONObject
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class DownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {
    @Inject
    lateinit var downloadManagerHelper: DownloadManagerHelper

    @Inject
    lateinit var cacheManager: CacheManager

    private val cancelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != ACTION_CANCEL_ALL_DOWNLOADS) return
            cancelAllManualDownloads()
        }
    }

    private fun cancelAllManualDownloads() {
        de.lwp2070809.speculonic.util.LogManager.i("DownloadService: User cancelled all manual downloads from notification.")
        if (cacheManager.isReleasing) return
        val manager = downloadManagerHelper.getDownloadManagerOnlyIfInitialized() ?: return

        isCancellingAll = true
        var removedCount = 0
        try {
            val activeDownloads = collectActiveDownloads(manager)
            for (download in activeDownloads) {
                if (!isSilent(download) && isActiveState(download.state)) {
                    manager.removeDownload(download.request.id)
                    removedCount++
                }
            }
            if (removedCount == 0) {
                isCancellingAll = false
            }
        } catch (e: Exception) {
            isCancellingAll = false
            de.lwp2070809.speculonic.util.LogManager.e("DownloadService: Failed to cancel downloads in cancelReceiver", e)
        }
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            syncCancellingFlagAndCheckSilent(downloadManager)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            syncCancellingFlagAndCheckSilent(downloadManager)
        }

        override fun onIdle(downloadManager: DownloadManager) {
            isCancellingAll = false
        }
    }

    private fun isActiveState(state: Int): Boolean {
        return state == Download.STATE_DOWNLOADING ||
            state == Download.STATE_QUEUED ||
            state == Download.STATE_RESTARTING
    }

    private fun collectActiveDownloads(manager: DownloadManager): List<Download> {
        if (manager.isInitialized) {
            return manager.currentDownloads
        }
        val active = mutableListOf<Download>()
        manager.downloadIndex.getDownloads(
            Download.STATE_DOWNLOADING,
            Download.STATE_QUEUED,
            Download.STATE_RESTARTING,
            Download.STATE_REMOVING
        ).use { cursor ->
            while (cursor.moveToNext()) {
                active.add(cursor.download)
            }
        }
        return active
    }

    private fun syncCancellingFlagAndCheckSilent(downloadManager: DownloadManager) {
        val hasManualPendingOrRemoving = downloadManager.currentDownloads.any {
            !isSilent(it) && (isActiveState(it.state) || it.state == Download.STATE_REMOVING)
        }
        if (!hasManualPendingOrRemoving) {
            isCancellingAll = false
        }
        checkAndStopIfSilent()
    }

    private fun checkAndStopIfSilent() {
        if (isSilentOnly()) {
            de.lwp2070809.speculonic.util.LogManager.i("DownloadService: Only silent downloads active. Stopping service.")
            stopSelf()
        }
    }

    private fun isSilentOnly(): Boolean {
        if (cacheManager.isReleasing) return false
        val manager = downloadManagerHelper.getDownloadManagerOnlyIfInitialized() ?: return false
        return try {
            val activeOrRemoving = collectActiveDownloads(manager)
                .filter { isActiveState(it.state) || it.state == Download.STATE_REMOVING }
            activeOrRemoving.isNotEmpty() && activeOrRemoving.all { isSilent(it) }
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.e("DownloadService: Failed to get downloads", e)
            false
        }
    }

    override fun onCreate() {
        de.lwp2070809.speculonic.util.LogManager.i("DownloadService: onCreate")
        super.onCreate()
        createNotificationChannels()
        val filter = android.content.IntentFilter(ACTION_CANCEL_ALL_DOWNLOADS)
        registerReceiver(
            cancelReceiver,
            filter,
            RECEIVER_NOT_EXPORTED
        )
        try {
            if (!cacheManager.isReleasing) {
                getDownloadManager().addListener(downloadListener)
            }
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.w("DownloadService: Failed to attach listener in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        de.lwp2070809.speculonic.util.LogManager.i("DownloadService: onStartCommand action=$action flags=$flags startId=$startId")

        if (cacheManager.isReleasing) {
            de.lwp2070809.speculonic.util.LogManager.w("DownloadService: Cache is being released, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        val result = try {
            val isSilentInitRestart = intent == null ||
                action == "androidx.media3.exoplayer.downloadService.action.INIT" ||
                action == "androidx.media3.exoplayer.downloadService.action.RESTART"
            if (isSilentOnly() && isSilentInitRestart) {
                de.lwp2070809.speculonic.util.LogManager.i("DownloadService: Intercepted silent download INIT action. Stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }
            super.onStartCommand(intent, flags, startId)
        } catch (e: IllegalStateException) {
            val isExpectedShutdownError = e.message.isNullOrBlank() ||
                e.message?.contains("dead thread") == true ||
                e.message?.contains("while cache is being released") == true
            if (isExpectedShutdownError) {
                de.lwp2070809.speculonic.util.LogManager.e("DownloadService: Caught IllegalStateException (${e.message}), stopping self.")
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        // 不在 onStartCommand 同步调用 checkAndStopIfSilent()，避免抢在 DownloadManager 异步处理
        // Resume/AddDownload 消息更新前读取陈旧状态导致手动下载恢复时被立即停服
        return result
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        de.lwp2070809.speculonic.util.LogManager.w(
            "DownloadService: Foreground service timed out (fgsType=$fgsType, startId=$startId). Stopping service gracefully."
        )
        try {
            downloadManagerHelper.getDownloadManagerOnlyIfInitialized()?.pauseDownloads()
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.e("DownloadService: Failed to pause downloads on timeout", e)
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        de.lwp2070809.speculonic.util.LogManager.i("DownloadService: onDestroy")
        isCancellingAll = false
        try {
            downloadManagerHelper.getDownloadManagerOnlyIfInitialized()?.removeListener(downloadListener)
        } catch (e: java.lang.Exception) {
            de.lwp2070809.speculonic.util.LogManager.w("DownloadService: Failed to remove download listener", e)
        }
        try {
            unregisterReceiver(cancelReceiver)
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.w("DownloadService: Failed to unregister cancel receiver", e)
        }
        super.onDestroy()
    }

    override fun getDownloadManager(): DownloadManager {
        return downloadManagerHelper.getDownloadManager()
    }

    override fun getScheduler(): Scheduler {
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        if (isCancellingAll) {
            return buildCancellingNotification()
        }

        val activeDownloads = downloads.filter { 
            it.state == Download.STATE_DOWNLOADING || 
            it.state == Download.STATE_QUEUED || 
            it.state == Download.STATE_RESTARTING 
        }
        
        if (activeDownloads.isNotEmpty()) {
            val clickIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val cancelIntent = android.content.Intent(ACTION_CANCEL_ALL_DOWNLOADS).setPackage(packageName)
            val cancelPendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                CANCEL_REQUEST_CODE,
                cancelIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.preparing_offline_content))
                .setContentIntent(clickIntent)
                .addAction(0, getString(R.string.cancel_downloads), cancelPendingIntent)
                .setSilent(true)
                .setOngoing(true)
                .build()
        }
        
        return buildSilentNotification()
    }


    private fun isSilent(download: Download): Boolean {
        return try {
            if (download.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
                json.optBoolean("isSilent", false)
            } else false
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.e("DownloadService: Failed to parse silent flag", e)
            false
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        
        
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val name = getString(R.string.download_channel_name)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.caching_in_background))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun buildCancellingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.cancel_downloads))
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile
        var isCancellingAll = false
        private const val JOB_ID = 1001
        private const val CANCEL_REQUEST_CODE = 1002
        const val ACTION_CANCEL_ALL_DOWNLOADS = "de.lwp2070809.speculonic.ACTION_CANCEL_ALL_DOWNLOADS"
        
        
        private const val FOREGROUND_NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "speculonic_download_channel"
    }
}
