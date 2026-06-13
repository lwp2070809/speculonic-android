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
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import org.json.JSONObject

@UnstableApi
@AndroidEntryPoint
class DownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {

    private val cancelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == ACTION_CANCEL_ALL_DOWNLOADS) {
                de.lwp2070809.speculonic.util.LogManager.i("DownloadService: User cancelled all manual downloads from notification.")
                isCancellingAll = true
                val manager = getDownloadManager()
                manager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val download = cursor.download
                        if (!isSilent(download) && (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED || download.state == Download.STATE_RESTARTING)) {
                            manager.removeDownload(download.request.id)
                        }
                    }
                }
                isCancellingAll = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val filter = android.content.IntentFilter(ACTION_CANCEL_ALL_DOWNLOADS)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(cancelReceiver)
        } catch (e: Exception) {
            de.lwp2070809.speculonic.util.LogManager.w("DownloadService: Failed to unregister cancel receiver", e)
        }
        super.onDestroy()
    }

    override fun getDownloadManager(): DownloadManager {
        return DownloadManagerHelper.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler {
        return PlatformScheduler(this, JOB_ID)
    }

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        if (isCancellingAll) {
            return buildCancellingNotification()
        }

        val visibleDownloads = downloads.filter { !isSilent(it) }
        val activeDownloads = visibleDownloads.filter { 
            it.state == Download.STATE_DOWNLOADING || 
            it.state == Download.STATE_QUEUED || 
            it.state == Download.STATE_RESTARTING 
        }
        
        if (activeDownloads.isNotEmpty()) {
            val currentDownload = activeDownloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
                ?: activeDownloads.firstOrNull() ?: return buildSilentNotification()

            
            val songTitle = try {
                if (currentDownload.request.data.isNotEmpty()) {
                    val json = JSONObject(Util.fromUtf8Bytes(currentDownload.request.data))
                    json.optString("title", getString(R.string.app_name))
                } else {
                    getString(R.string.app_name)
                }
            } catch (e: Exception) {
                getString(R.string.app_name)
            }

            
            val activeCount = activeDownloads.size

            val progressTitle = getString(R.string.downloading_progress_simple, activeCount)

            val clickIntent = android.app.PendingIntent.getActivity(
                this, 0,
                android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val cancelIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                android.content.Intent(this, MainActivity::class.java).apply {
                    action = "de.lwp2070809.speculonic.ACTION_ASK_CANCEL_DOWNLOADS"
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(songTitle)
                .setContentText(progressTitle)
                .setContentIntent(clickIntent)
                .setProgress(0, 0, true)
                .setSilent(true)
                .setOngoing(true)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    getString(R.string.cancel_downloads),
                    cancelIntent
                )
                .build()
        }
        
        
        if (visibleDownloads.isNotEmpty()) {
            val clickIntent = android.app.PendingIntent.getActivity(
                this, 0,
                android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.sync_completed))
                .setContentIntent(clickIntent)
                .setAutoCancel(true)
                .setSilent(true)
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

        
        if (manager.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            val name = getString(R.string.silent_cache_channel_name)
            val descriptionText = getString(R.string.silent_cache_channel_description)
            val channel = NotificationChannel(
                SILENT_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = descriptionText
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        return NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.caching_in_background))
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
        const val ACTION_CANCEL_ALL_DOWNLOADS = "de.lwp2070809.speculonic.ACTION_CANCEL_ALL_DOWNLOADS"
        
        
        private const val FOREGROUND_NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "speculonic_download_channel"
        private const val SILENT_CHANNEL_ID = "speculonic_silent_channel"
    }
}
