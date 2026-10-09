package de.lwp2070809.speculonic.playback

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackSleepTimerManager(
    private val scope: CoroutineScope,
    private val mediaSessionProvider: () -> MediaSession?
) {
    private var sleepTimerJob: Job? = null
    var sleepTimerMode: String = "OFF"
        private set
    private var sleepTimerDeadlineRealtime = 0L
    private var sleepTimerSongsRemaining = 0
    private var timerLastMediaId: String? = null

    fun setSleepTimer(mode: String, minutes: Int = 0, songCount: Int = 0) {
        sleepTimerJob?.cancel()
        sleepTimerMode = mode
        val session = mediaSessionProvider()
        timerLastMediaId = session?.player?.currentMediaItem?.mediaId
        de.lwp2070809.speculonic.util.LogManager.i(
            de.lwp2070809.speculonic.util.LogTag.PLAYBACK,
            "Sleep timer configured -> mode=$mode, minutes=$minutes, songCount=$songCount"
        )

        when (mode) {
            "OFF" -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = 0
                broadcastSleepTimerState()
            }
            "TIME" -> {
                val totalMillis = minutes * 60 * 1000L
                sleepTimerDeadlineRealtime = SystemClock.elapsedRealtime() + totalMillis
                broadcastSleepTimerState()
                sleepTimerJob = scope.launch {
                    while (isActive) {
                        val remaining = sleepTimerDeadlineRealtime - SystemClock.elapsedRealtime()
                        if (remaining <= 0) {
                            LogManager.i("PlaybackSleepTimerManager: Sleep timer expired! Pausing player.")
                            withContext(Dispatchers.Main) {
                                mediaSessionProvider()?.player?.pause()
                                cancelSleepTimer()
                            }
                            break
                        }
                        // 挂起等待至到期时间，避免后台高频唤醒
                        delay(remaining.coerceAtLeast(100L))
                    }
                }
            }
            "SONG_COUNT" -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = songCount
                broadcastSleepTimerState()
            }
            "END_OF_PLAYLIST" -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = 0
                broadcastSleepTimerState()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerMode = "OFF"
        sleepTimerDeadlineRealtime = 0L
        sleepTimerSongsRemaining = 0
        timerLastMediaId = null
        de.lwp2070809.speculonic.util.LogManager.i(de.lwp2070809.speculonic.util.LogTag.PLAYBACK, "Sleep timer cancelled")
        broadcastSleepTimerState()
    }

    fun handlePlaybackEndedForTimer() {
        if (sleepTimerMode == "OFF") return
        LogManager.i("PlaybackSleepTimerManager: Playback ended. Cancelling sleep timer.")
        cancelSleepTimer()
    }

    fun handleMediaItemTransitionForTimer(mediaItem: MediaItem?, reason: Int) {
        if (sleepTimerMode == "OFF") return
        val currentId = mediaItem?.mediaId ?: return
        val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        val isAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        val isItemChanged = currentId != timerLastMediaId

        if (!isItemChanged && !isRepeat && !isAuto) return
        val previousMediaId = timerLastMediaId
        timerLastMediaId = currentId

        val session = mediaSessionProvider()
        if (sleepTimerMode == "SONG_COUNT") {
            sleepTimerSongsRemaining--
            if (sleepTimerSongsRemaining <= 0) {
                LogManager.i("PlaybackSleepTimerManager: Sleep timer song count reached. Pausing player.")
                session?.player?.pause()
                cancelSleepTimer()
            } else {
                broadcastSleepTimerState()
            }
        } else if (sleepTimerMode == "END_OF_PLAYLIST") {
            val player = session?.player
            val isRepeatOne = player?.repeatMode == Player.REPEAT_MODE_ONE
            val isSingleItemRepeatAll = player?.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount == 1
            if (isRepeatOne || (isSingleItemRepeatAll && isRepeat)) {
                LogManager.i("PlaybackSleepTimerManager: Sleep timer end of playlist reached under single item repeat mode. Pausing player.")
                player.pause()
                cancelSleepTimer()
            } else if (isAuto) {
                val timeline = player?.currentTimeline
                if (timeline != null && !timeline.isEmpty) {
                    val lastWindowIndex = timeline.getLastWindowIndex(player.shuffleModeEnabled)
                    val firstWindowIndex = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
                    val lastMediaItem = if (lastWindowIndex in 0 until player.mediaItemCount) {
                        player.getMediaItemAt(lastWindowIndex)
                    } else null
                    val isLoopBack = player.currentMediaItemIndex == firstWindowIndex && lastMediaItem?.mediaId == previousMediaId
                    val isSingleItemAuto = player.mediaItemCount == 1 && lastMediaItem?.mediaId == previousMediaId
                    if (isLoopBack || isSingleItemAuto) {
                        LogManager.i("PlaybackSleepTimerManager: Sleep timer end of playlist reached. Pausing player.")
                        player.pause()
                        cancelSleepTimer()
                    }
                }
            }
        }
    }

    fun broadcastSleepTimerState() {
        val session = mediaSessionProvider() ?: return
        val currentExtras = session.sessionExtras
        val remainingMillis = if (sleepTimerMode == "TIME") {
            (sleepTimerDeadlineRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else 0L

        val newExtras = Bundle(currentExtras).apply {
            putString("sleepTimerMode", sleepTimerMode)
            putBoolean("isSleepTimerRunning", sleepTimerMode != "OFF")
            putLong("sleepTimerRemainingMillis", remainingMillis)
            putLong("sleepTimerDeadlineRealtime", sleepTimerDeadlineRealtime)
            putInt("sleepTimerSongsRemaining", sleepTimerSongsRemaining)
        }
        session.sessionExtras = newExtras
    }

    fun release() {
        sleepTimerJob?.cancel()
    }
}
