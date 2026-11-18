package de.lwp2070809.speculonic.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.util.LogManager
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class SilentDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID_NONE
) {
    @Inject
    lateinit var downloadManagerHelper: DownloadManagerHelper

    @Inject
    lateinit var cacheManager: CacheManager

    override fun getDownloadManager(): DownloadManager {
        return downloadManagerHelper.getDownloadManager()
    }

    override fun getScheduler(): Scheduler? {
        return null // 静默下载随播放运行，无需后台 Job 调度
    }

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): android.app.Notification {
        throw UnsupportedOperationException("SilentDownloadService should not show notifications")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (cacheManager.isReleasing) {
            LogManager.w("SilentDownloadService: Cache is being released, stopping self.")
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: IllegalStateException) {
            val isExpectedShutdownError = e.message.isNullOrBlank() ||
                e.message?.contains("dead thread") == true ||
                e.message?.contains("while cache is being released") == true
            if (isExpectedShutdownError) {
                LogManager.e("SilentDownloadService: Caught IllegalStateException (${e.message}), stopping self.")
                stopSelf()
                START_NOT_STICKY
            } else {
                throw e
            }
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        LogManager.w(
            "SilentDownloadService: Foreground service timed out (fgsType=$fgsType, startId=$startId). Stopping self."
        )
        try {
            downloadManagerHelper.getDownloadManagerOnlyIfInitialized()?.pauseDownloads()
        } catch (e: Exception) {
            LogManager.e("SilentDownloadService: Failed to pause downloads on timeout", e)
        }
        stopSelf(startId)
    }
}
