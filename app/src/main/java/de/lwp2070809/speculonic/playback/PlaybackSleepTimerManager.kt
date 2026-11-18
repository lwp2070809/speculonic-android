package de.lwp2070809.speculonic.playback

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
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
    var sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF
        private set
    private var sleepTimerDeadlineRealtime = 0L
    private var sleepTimerSongsRemaining = 0
    // 使用队列窗口索引而非 mediaId 追踪前驱项，避免歌单中含重复 songId 时误判末曲回环或漏减计数
    private var timerLastWindowIndex: Int = C.INDEX_UNSET
    private var timerLastMediaId: String? = null

    fun setSleepTimer(mode: SleepTimerMode, minutes: Int = 0, songCount: Int = 0) {
        sleepTimerJob?.cancel()
        sleepTimerMode = mode
        val session = mediaSessionProvider()
        timerLastWindowIndex = session?.player?.currentMediaItemIndex ?: C.INDEX_UNSET
        timerLastMediaId = session?.player?.currentMediaItem?.mediaId
        LogManager.i(
            LogTag.PLAYBACK,
            "Sleep timer configured -> mode=${mode.name}, minutes=$minutes, songCount=$songCount"
        )

        when (mode) {
            SleepTimerMode.OFF -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = 0
                timerLastWindowIndex = C.INDEX_UNSET
                timerLastMediaId = null
                broadcastSleepTimerState()
            }
            SleepTimerMode.TIME -> {
                val totalMillis = minutes * MILLIS_PER_MINUTE
                sleepTimerDeadlineRealtime = SystemClock.elapsedRealtime() + totalMillis
                broadcastSleepTimerState()
                sleepTimerJob = scope.launch {
                    while (isActive) {
                        val remaining = sleepTimerDeadlineRealtime - SystemClock.elapsedRealtime()
                        if (remaining <= 0) {
                            LogManager.i("PlaybackSleepTimerManager: Sleep timer expired! Pausing player.")
                            withContext(Dispatchers.Main) {
                                pausePlayerAndReset(mediaSessionProvider()?.player)
                                cancelSleepTimer()
                            }
                            break
                        }
                        // 挂起等待至到期时间，避免后台高频唤醒
                        delay(remaining.coerceAtLeast(MIN_TIMER_DELAY_MS))
                    }
                }
            }
            SleepTimerMode.SONG_COUNT -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = songCount
                broadcastSleepTimerState()
            }
            SleepTimerMode.END_OF_PLAYLIST -> {
                sleepTimerDeadlineRealtime = 0L
                sleepTimerSongsRemaining = 0
                broadcastSleepTimerState()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerMode = SleepTimerMode.OFF
        sleepTimerDeadlineRealtime = 0L
        sleepTimerSongsRemaining = 0
        timerLastWindowIndex = C.INDEX_UNSET
        timerLastMediaId = null
        LogManager.i(LogTag.PLAYBACK, "Sleep timer cancelled")
        broadcastSleepTimerState()
    }

    fun handlePlaybackEndedForTimer() {
        if (sleepTimerMode == SleepTimerMode.OFF) return
        LogManager.i("PlaybackSleepTimerManager: Playback ended. Stopping player and cancelling sleep timer.")
        pausePlayerAndReset(mediaSessionProvider()?.player)
        cancelSleepTimer()
    }

    fun handleTimelineChangedForTimer(player: Player) {
        if (sleepTimerMode == SleepTimerMode.OFF) return
        if (player.mediaItemCount == 0 || player.currentTimeline.isEmpty) {
            LogManager.i("PlaybackSleepTimerManager: Playlist emptied while timer active, cancelling sleep timer.")
            cancelSleepTimer()
            return
        }
        val currentWindowIndex = player.currentMediaItemIndex
        val currentMediaId = player.currentMediaItem?.mediaId
        if (currentWindowIndex != C.INDEX_UNSET && currentMediaId != null && currentMediaId == timerLastMediaId) {
            timerLastWindowIndex = currentWindowIndex
        }
    }

    fun handleMediaItemTransitionForTimer(mediaItem: MediaItem?, reason: Int) {
        if (sleepTimerMode == SleepTimerMode.OFF || mediaItem == null) return
        val player = mediaSessionProvider()?.player ?: return
        val currentWindowIndex = player.currentMediaItemIndex
        val previousWindowIndex = timerLastWindowIndex
        timerLastWindowIndex = currentWindowIndex
        timerLastMediaId = mediaItem.mediaId

        val isNaturalTransition =
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (!isNaturalTransition) {
            return
        }

        when (sleepTimerMode) {
            SleepTimerMode.SONG_COUNT -> handleSongCountTransition(player)
            SleepTimerMode.END_OF_PLAYLIST -> handleEndOfPlaylistTransition(
                player = player,
                reason = reason,
                currentWindowIndex = currentWindowIndex,
                previousWindowIndex = previousWindowIndex
            )
            SleepTimerMode.OFF, SleepTimerMode.TIME -> Unit
        }
    }

    private fun handleSongCountTransition(player: Player) {
        sleepTimerSongsRemaining--
        if (sleepTimerSongsRemaining <= 0) {
            LogManager.i("PlaybackSleepTimerManager: Sleep timer song count reached. Pausing player.")
            pausePlayerAndReset(player)
            cancelSleepTimer()
        } else {
            broadcastSleepTimerState()
        }
    }

    private fun handleEndOfPlaylistTransition(
        player: Player,
        reason: Int,
        currentWindowIndex: Int,
        previousWindowIndex: Int
    ) {
        val isRepeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        val isSingleItemRepeatAll = player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount == 1
        if (isRepeatOne || (isSingleItemRepeatAll && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
            LogManager.i("PlaybackSleepTimerManager: Sleep timer end of playlist reached under repeat mode. Pausing player.")
            pausePlayerAndReset(player)
            cancelSleepTimer()
            return
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val timeline = player.currentTimeline
            if (!timeline.isEmpty && player.mediaItemCount > 0) {
                val lastWindowIndex = timeline.getLastWindowIndex(player.shuffleModeEnabled)
                val firstWindowIndex = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
                val isLoopBack = currentWindowIndex == firstWindowIndex && previousWindowIndex == lastWindowIndex
                val isSingleItemAuto = player.mediaItemCount == 1 && previousWindowIndex == 0
                if (isLoopBack || isSingleItemAuto) {
                    LogManager.i("PlaybackSleepTimerManager: Sleep timer end of playlist reached. Pausing player.")
                    pausePlayerAndReset(player)
                    cancelSleepTimer()
                }
            }
        }
    }

    private fun pausePlayerAndReset(player: Player?) {
        player ?: return
        player.playWhenReady = false
        player.pause()
    }

    fun broadcastSleepTimerState() {
        val session = mediaSessionProvider() ?: return
        val currentExtras = session.sessionExtras
        val remainingMillis = if (sleepTimerMode == SleepTimerMode.TIME) {
            (sleepTimerDeadlineRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            0L
        }

        val newExtras = Bundle(currentExtras).apply {
            putString("sleepTimerMode", sleepTimerMode.name)
            putBoolean("isSleepTimerRunning", sleepTimerMode != SleepTimerMode.OFF)
            putLong("sleepTimerRemainingMillis", remainingMillis)
            putLong("sleepTimerDeadlineRealtime", sleepTimerDeadlineRealtime)
            putInt("sleepTimerSongsRemaining", sleepTimerSongsRemaining)
        }
        session.sessionExtras = newExtras
    }

    fun release() {
        sleepTimerJob?.cancel()
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MIN_TIMER_DELAY_MS = 100L
    }
}
