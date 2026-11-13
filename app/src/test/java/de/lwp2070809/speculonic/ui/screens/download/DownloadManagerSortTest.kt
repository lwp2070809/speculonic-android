package de.lwp2070809.speculonic.ui.screens.download

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lwp2070809.speculonic.data.DownloadTaskInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 离线下载与静默缓存列表排序规则单元测试。
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DownloadManagerSortTest {

    private fun createTask(
        id: String,
        state: Int,
        startTimeMs: Long,
        updateTimeMs: Long = startTimeMs,
        isSilent: Boolean = false
    ): DownloadTaskInfo {
        val data = Util.getUtf8Bytes(JSONObject().put("isSilent", isSilent).toString())
        val request = DownloadRequest.Builder(id, Uri.parse("https://example.com/$id"))
            .setData(data)
            .build()
        val download = Download(
            request,
            state,
            startTimeMs,
            updateTimeMs,
            1000L,
            if (state == Download.STATE_STOPPED) 1 else Download.STOP_REASON_NONE,
            if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE
        )
        return DownloadTaskInfo(task = download, progress = 0f, state = state, isSilent = isSilent)
    }

    @Test
    fun sortDownloadTasks_prioritizesDownloadingThenQueuedThenStoppedFailedThenCompleted() {
        val tasks = listOf(
            createTask("completed_old", Download.STATE_COMPLETED, startTimeMs = 100L, updateTimeMs = 1000L),
            createTask("stopped_new", Download.STATE_STOPPED, startTimeMs = 200L, updateTimeMs = 2500L),
            createTask("queued_later", Download.STATE_QUEUED, startTimeMs = 400L, updateTimeMs = 400L),
            createTask("failed_old", Download.STATE_FAILED, startTimeMs = 150L, updateTimeMs = 2000L),
            createTask("downloading_later", Download.STATE_DOWNLOADING, startTimeMs = 350L, updateTimeMs = 900L),
            createTask("completed_new", Download.STATE_COMPLETED, startTimeMs = 500L, updateTimeMs = 3000L),
            createTask("queued_earlier", Download.STATE_QUEUED, startTimeMs = 300L, updateTimeMs = 300L),
            createTask("restarting", Download.STATE_RESTARTING, startTimeMs = 250L, updateTimeMs = 950L),
            createTask("silent_downloading", Download.STATE_DOWNLOADING, startTimeMs = 50L, isSilent = true)
        )

        val manualSorted = sortDownloadTasks(tasks, isSilent = false).map { it.task.request.id }
        assertEquals(
            listOf(
                "restarting",
                "downloading_later",
                "queued_earlier",
                "queued_later",
                "stopped_new",
                "failed_old",
                "completed_new",
                "completed_old"
            ),
            manualSorted
        )

        val silentSorted = sortDownloadTasks(tasks, isSilent = true).map { it.task.request.id }
        assertEquals(listOf("silent_downloading"), silentSorted)
    }

    @Test
    fun sortDownloadTasks_tieBreaksEqualTimestampsDeterministically() {
        val tasks = listOf(
            createTask("q1", Download.STATE_QUEUED, startTimeMs = 1000L),
            createTask("q2", Download.STATE_QUEUED, startTimeMs = 1000L),
            createTask("c1", Download.STATE_COMPLETED, startTimeMs = 1000L, updateTimeMs = 2000L),
            createTask("c2", Download.STATE_COMPLETED, startTimeMs = 1000L, updateTimeMs = 2000L)
        )

        val sorted = sortDownloadTasks(tasks, isSilent = false).map { it.task.request.id }
        assertEquals(listOf("q1", "q2", "c2", "c1"), sorted)
    }
}
