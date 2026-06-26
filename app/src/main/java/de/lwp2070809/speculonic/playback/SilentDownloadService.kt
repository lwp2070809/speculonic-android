package de.lwp2070809.speculonic.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.data.DownloadManagerHelper

@UnstableApi
@AndroidEntryPoint
class SilentDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID_NONE
) {

    override fun getDownloadManager(): DownloadManager {
        return DownloadManagerHelper.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler? {
        return null // 静默下载随播放运行，无需后台 Job 调度
    }

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): android.app.Notification {
        throw UnsupportedOperationException("SilentDownloadService should not show notifications")
    }
}
