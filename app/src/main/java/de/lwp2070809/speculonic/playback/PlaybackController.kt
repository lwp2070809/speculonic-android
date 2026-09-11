package de.lwp2070809.speculonic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SleepTimerMode {
    OFF, TIME, SONG_COUNT, END_OF_PLAYLIST
}

data class PlaybackState(
    val currentSongId: String = "",
    val currentSongTitle: String = "",
    val currentArtist: String = "",
    val artworkUri: Uri? = null,
    val artworkId: String? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
    val currentQueue: List<MediaItem> = emptyList(),
    val currentIndex: Int = -1,
    val sleepTimerRemainingMillis: Long = 0L,
    val isSleepTimerRunning: Boolean = false,
    val queueTitle: String? = null,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerSongsRemaining: Int = 0
)

class PlaybackController private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: PlaybackController? = null

        fun getInstance(context: Context): PlaybackController {
            return instance ?: synchronized(this) {
                instance ?: PlaybackController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val preferencesManager = PreferencesManager.getInstance(appContext)
    private val db = de.lwp2070809.speculonic.data.db.AppDatabase.getDatabase(appContext)
    private val musicDao = db.musicDao()
    
    private val controller: MediaController? get() {
        val future = controllerFuture ?: return null
        if (!future.isDone) return null
        return try {
            future.get()
        } catch (e: Exception) {
            LogManager.w("PlaybackController: MediaController connection failed: ${e.message}")
            null
        } catch (e: Error) {
            LogManager.e("PlaybackController: Fatal error getting controller", e)
            null
        }
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var sleepTimerDeadlineRealtime: Long = 0L
    
    private var isAppVisible = true
    private val pendingActions = java.util.concurrent.CopyOnWriteArrayList<(MediaController) -> Unit>()

    private fun executeWhenReady(action: (MediaController) -> Unit) {
        ensureController()
        val ctrl = controller
        if (ctrl != null) {
            action(ctrl)
        } else {
            LogManager.i("PlaybackController: Controller not ready, queueing action")
            pendingActions.add(action)
        }
    }

    fun ensureController() {
        val currentFuture = controllerFuture
        if (currentFuture != null) {
            if (!currentFuture.isDone) return
            try {
                currentFuture.get()
                return 
            } catch (e: Exception) {
                LogManager.w("PlaybackController: Current controller future is in error state, re-initializing...")
                controllerFuture = null
            }
        }
        
        LogManager.i("PlaybackController: Initializing MediaController...")
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val newFuture = MediaController.Builder(appContext, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(
                    controller: MediaController,
                    extras: android.os.Bundle
                ) {
                    super.onExtrasChanged(controller, extras)
                    updateState()
                }
            })
            .buildAsync()
        controllerFuture = newFuture
        newFuture.addListener({
            try {
                setupController()
            } catch (e: Throwable) {
                LogManager.e("PlaybackController: Failed to setup controller after connection", e)
                controllerFuture = null
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController() {
        val controller = controller ?: return
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                updateState(events)
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || 
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    handleProgressTicker()
                }
            }
        })
        
        updateState()
        handleProgressTicker()
        
        if (pendingActions.isNotEmpty()) {
            LogManager.i("PlaybackController: Executing ${pendingActions.size} pending actions")
            pendingActions.forEach { it(controller) }
            pendingActions.clear()
        }
    }

    fun onAppVisibilityChanged(visible: Boolean) {
        isAppVisible = visible
        handleProgressTicker()
    }

    private fun handleProgressTicker() {
        progressJob?.cancel()
        val controller = controller ?: return
        
        if (controller.isPlaying && isAppVisible) {
            progressJob = scope.launch {
                while (isActive) {
                    updatePosition()
                    delay(250)
                }
            }
        }
    }

    private fun getEffectiveDuration(controller: Player, mediaItem: MediaItem? = controller.currentMediaItem): Long {
        val rawDuration = controller.duration
        if (rawDuration > 0L && rawDuration != androidx.media3.common.C.TIME_UNSET) {
            return rawDuration
        }
        val metaDuration = mediaItem?.mediaMetadata?.durationMs
        if (metaDuration != null && metaDuration > 0L && metaDuration != androidx.media3.common.C.TIME_UNSET) {
            return metaDuration
        }
        val extrasDuration = mediaItem?.mediaMetadata?.extras?.getLong("durationMs", 0L) ?: 0L
        if (extrasDuration > 0L) {
            return extrasDuration
        }
        return 0L
    }

    private fun updatePosition() {
        val controller = controller ?: return
        val remainingMillis = if (_playbackState.value.isSleepTimerRunning && sleepTimerDeadlineRealtime > 0L) {
            (sleepTimerDeadlineRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            _playbackState.value.sleepTimerRemainingMillis
        }
        _playbackState.value = _playbackState.value.copy(
            currentPosition = controller.currentPosition,
            duration = getEffectiveDuration(controller),
            sleepTimerRemainingMillis = remainingMillis
        )
    }

    private fun updateState(events: Player.Events? = null) {
        val controller = controller ?: return
        val currentMediaItem = controller.currentMediaItem
        
        val queue = if (events == null || events.contains(Player.EVENT_TIMELINE_CHANGED)) {
            val newQueue = mutableListOf<MediaItem>()
            for (i in 0 until controller.mediaItemCount) {
                newQueue.add(controller.getMediaItemAt(i))
            }
            newQueue
        } else {
            _playbackState.value.currentQueue
        }

        val extras = currentMediaItem?.mediaMetadata?.extras
        val artworkId = extras?.getString("coverArtId")
        val realTitle = extras?.getString("realTitle") ?: currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        val realArtist = extras?.getString("realArtist") ?: currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
        val sessionExtras = controller.sessionExtras
        val queueTitle = sessionExtras.getString("queueTitle")

        val timerModeStr = sessionExtras.getString("sleepTimerMode", SleepTimerMode.OFF.name)
        val isTimerRunning = sessionExtras.getBoolean("isSleepTimerRunning", false)
        val deadlineRealtime = sessionExtras.getLong("sleepTimerDeadlineRealtime", 0L)
        val songsRemaining = sessionExtras.getInt("sleepTimerSongsRemaining", 0)
        val timerMode = try {
            SleepTimerMode.valueOf(timerModeStr)
        } catch (e: Exception) {
            SleepTimerMode.OFF
        }
        if (deadlineRealtime > 0L) {
            sleepTimerDeadlineRealtime = deadlineRealtime
        } else if (!isTimerRunning) {
            sleepTimerDeadlineRealtime = 0L
        }
        val remainingMillis = if (sleepTimerDeadlineRealtime > 0L) {
            (sleepTimerDeadlineRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            sessionExtras.getLong("sleepTimerRemainingMillis", 0L)
        }

        _playbackState.value = _playbackState.value.copy(
            currentSongId = currentMediaItem?.mediaId ?: "",
            currentSongTitle = realTitle,
            currentArtist = realArtist,
            artworkUri = currentMediaItem?.mediaMetadata?.artworkUri,
            artworkId = artworkId,
            isPlaying = controller.isPlaying,
            currentPosition = controller.currentPosition,
            duration = getEffectiveDuration(controller, currentMediaItem),
            repeatMode = controller.repeatMode,
            shuffleModeEnabled = controller.shuffleModeEnabled,
            currentQueue = queue,
            currentIndex = controller.currentMediaItemIndex,
            queueTitle = queueTitle,
            sleepTimerRemainingMillis = remainingMillis,
            isSleepTimerRunning = isTimerRunning,
            sleepTimerMode = timerMode,
            sleepTimerSongsRemaining = songsRemaining
        )
    }

    fun play(mediaItems: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false, queueTitle: String? = null) {
        _playbackState.value = _playbackState.value.copy(queueTitle = queueTitle)
        executeWhenReady { controller ->
            scope.launch {
                val savedRepeatMode = preferencesManager.repeatMode.first()
                val savedShuffleMode = preferencesManager.shuffleMode.first()
                
                withContext(Dispatchers.Main) {
                    val bundle = android.os.Bundle().apply { putString("queueTitle", queueTitle) }
                    controller.sendCustomCommand(
                        androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY),
                        bundle
                    )

                    controller.repeatMode = savedRepeatMode
                    controller.shuffleModeEnabled = if (shuffle) true else savedShuffleMode
                    
                    controller.setMediaItems(mediaItems, startIndex, 0L)
                    controller.prepare()
                    controller.play()
                }
            }
        }
    }


    fun addMediaItems(mediaItems: List<MediaItem>) {
        executeWhenReady { controller ->
            controller.addMediaItems(mediaItems)
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
        }
    }

    fun togglePlayPause() {
        executeWhenReady { controller ->
            if (controller.isPlaying) {
                controller.pause()
            } else {
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                controller.play()
            }
        }
    }

    fun skipToNext() {
        executeWhenReady { controller ->
            controller.seekToNext()
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
                controller.play()
            }
        }
    }

    fun skipToPrevious() {
        executeWhenReady { controller ->
            controller.seekToPrevious()
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
                controller.play()
            }
        }
    }
    
    fun seekTo(position: Long) {
        executeWhenReady { controller ->
            controller.seekTo(position)
            updatePosition()
        }
    }

    fun togglePlaybackMode() {
        executeWhenReady { controller ->
            when {
                !controller.shuffleModeEnabled && (controller.repeatMode == Player.REPEAT_MODE_ALL || controller.repeatMode == Player.REPEAT_MODE_OFF) -> {
                    controller.repeatMode = Player.REPEAT_MODE_ONE
                    controller.shuffleModeEnabled = false
                }
                !controller.shuffleModeEnabled && controller.repeatMode == Player.REPEAT_MODE_ONE -> {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.shuffleModeEnabled = true
                }
                else -> {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.shuffleModeEnabled = false
                }
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        setSleepTimer(SleepTimerMode.TIME, minutes = minutes)
    }

    fun setSleepTimer(mode: SleepTimerMode, minutes: Int = 0, songCount: Int = 0) {
        val totalMillis = minutes * 60 * 1000L
        val deadline = if (mode == SleepTimerMode.TIME) {
            android.os.SystemClock.elapsedRealtime() + totalMillis
        } else 0L
        sleepTimerDeadlineRealtime = deadline

        _playbackState.value = _playbackState.value.copy(
            sleepTimerRemainingMillis = totalMillis,
            isSleepTimerRunning = mode != SleepTimerMode.OFF,
            sleepTimerMode = mode,
            sleepTimerSongsRemaining = songCount
        )

        executeWhenReady { controller ->
            val args = android.os.Bundle().apply {
                putString("mode", mode.name)
                putInt("minutes", minutes)
                putInt("songCount", songCount)
            }
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("SET_SLEEP_TIMER", android.os.Bundle.EMPTY),
                args
            )
        }
    }

    fun cancelSleepTimer() {
        sleepTimerDeadlineRealtime = 0L
        _playbackState.value = _playbackState.value.copy(
            sleepTimerRemainingMillis = 0L,
            isSleepTimerRunning = false,
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerSongsRemaining = 0
        )
        executeWhenReady { controller ->
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("CANCEL_SLEEP_TIMER", android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
        }
    }

    fun skipToQueueItem(index: Int) {
        executeWhenReady { controller ->
            if (index >= 0 && index < controller.mediaItemCount) {
                controller.seekTo(index, 0L)
                controller.play()
            } else {
                LogManager.w("PlaybackController: skipToQueueItem out of bounds ($index), ignoring.")
            }
        }
    }

    fun removeFromQueue(index: Int) {
        executeWhenReady { controller ->
            if (index in 0 until controller.mediaItemCount) {
                if (index == controller.currentMediaItemIndex) {
                    if (controller.mediaItemCount == 1) {
                        controller.stop()
                        controller.clearMediaItems()
                    } else if (index == controller.mediaItemCount - 1) {
                        controller.seekTo(0, 0L)
                        controller.removeMediaItem(index)
                    } else {
                        controller.seekToNext()
                        controller.removeMediaItem(index)
                    }
                } else {
                    controller.removeMediaItem(index)
                }
            }
        }
    }

    fun release() {
        scope.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        controllerFuture = null
    }
}
