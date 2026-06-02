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
            controllerFuture = null 
            null
        } catch (e: Error) {
            LogManager.e("PlaybackController: Fatal error getting controller", e)
            controllerFuture = null
            null
        }
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState = _playbackState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    
    private var isAppVisible = true
    private var songsPlayedSinceTimerStarted = 0
    private var lastMediaId: String? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

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
        val newFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture = newFuture
        newFuture.addListener({
            try {
                setupController()
            } catch (e: Throwable) {
                LogManager.e("PlaybackController: Failed to setup controller after connection", e)
                controllerFuture = null
            }
        }, MoreExecutors.directExecutor())
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

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                handleMediaItemTransition(reason)
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

    private fun handleMediaItemTransition(reason: Int) {
        val controller = controller ?: return
        val currentId = controller.currentMediaItem?.mediaId
        if (currentId != null && currentId != lastMediaId) {
            lastMediaId = currentId
            
            if (_playbackState.value.isSleepTimerRunning) {
                if (_playbackState.value.sleepTimerMode == SleepTimerMode.SONG_COUNT) {
                    val remaining = _playbackState.value.sleepTimerSongsRemaining - 1
                    if (remaining <= 0) {
                        pauseAndStopTimer()
                    } else {
                        _playbackState.value = _playbackState.value.copy(sleepTimerSongsRemaining = remaining)
                    }
                } else if (_playbackState.value.sleepTimerMode == SleepTimerMode.END_OF_PLAYLIST) {
                    songsPlayedSinceTimerStarted++
                    val queueSize = controller.mediaItemCount
                    val isRepeatOne = controller.repeatMode == Player.REPEAT_MODE_ONE
                    
                    if (isRepeatOne) {
                        pauseAndStopTimer()
                    } else if (controller.shuffleModeEnabled) {
                        if (songsPlayedSinceTimerStarted >= queueSize) {
                            pauseAndStopTimer()
                        }
                    }
                }
            }
        }
    }

    private fun pauseAndStopTimer() {
        controller?.pause()
        cancelSleepTimer()
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

    private fun updatePosition() {
        val controller = controller ?: return
        _playbackState.value = _playbackState.value.copy(
            currentPosition = controller.currentPosition,
            duration = controller.duration
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
        val queueTitle = controller.sessionExtras.getString("queueTitle")

        _playbackState.value = _playbackState.value.copy(
            currentSongId = currentMediaItem?.mediaId ?: "",
            currentSongTitle = realTitle,
            currentArtist = realArtist,
            artworkUri = currentMediaItem?.mediaMetadata?.artworkUri,
            artworkId = artworkId,
            isPlaying = controller.isPlaying,
            currentPosition = controller.currentPosition,
            duration = controller.duration,
            repeatMode = controller.repeatMode,
            shuffleModeEnabled = controller.shuffleModeEnabled,
            currentQueue = queue,
            currentIndex = controller.currentMediaItemIndex,
            queueTitle = queueTitle ?: _playbackState.value.queueTitle
        )
    }

    fun play(mediaItems: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false, queueTitle: String? = null) {
        executeWhenReady { controller ->
        
        scope.launch {
            val savedRepeatMode = preferencesManager.repeatMode.first()
            val savedShuffleMode = preferencesManager.shuffleMode.first()
            
            withContext(Dispatchers.Main) {
                queueTitle?.let {
                    val bundle = android.os.Bundle().apply { putString("queueTitle", it) }
                    controller.sendCustomCommand(androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY), bundle)
                    _playbackState.value = _playbackState.value.copy(queueTitle = it)
                }

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
                controller.shuffleModeEnabled -> {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.shuffleModeEnabled = false
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
        sleepTimerJob?.cancel()
        songsPlayedSinceTimerStarted = 0
        lastMediaId = controller?.currentMediaItem?.mediaId

        when (mode) {
            SleepTimerMode.OFF -> {
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerRemainingMillis = 0L,
                    isSleepTimerRunning = false,
                    sleepTimerMode = SleepTimerMode.OFF
                )
            }
            SleepTimerMode.TIME -> {
                val totalMillis = minutes * 60 * 1000L
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerRemainingMillis = totalMillis,
                    isSleepTimerRunning = true,
                    sleepTimerMode = SleepTimerMode.TIME
                )
                sleepTimerJob = scope.launch {
                    var remaining = totalMillis
                    while (remaining > 0) {
                        delay(1000)
                        remaining -= 1000
                        _playbackState.value = _playbackState.value.copy(
                            sleepTimerRemainingMillis = remaining.coerceAtLeast(0)
                        )
                    }
                    pauseAndStopTimer()
                }
            }
            SleepTimerMode.SONG_COUNT -> {
                _playbackState.value = _playbackState.value.copy(
                    isSleepTimerRunning = true,
                    sleepTimerMode = SleepTimerMode.SONG_COUNT,
                    sleepTimerSongsRemaining = songCount
                )
            }
            SleepTimerMode.END_OF_PLAYLIST -> {
                _playbackState.value = _playbackState.value.copy(
                    isSleepTimerRunning = true,
                    sleepTimerMode = SleepTimerMode.END_OF_PLAYLIST
                )
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(
            sleepTimerRemainingMillis = 0L,
            isSleepTimerRunning = false,
            sleepTimerMode = SleepTimerMode.OFF,
            sleepTimerSongsRemaining = 0
        )
    }

    fun skipToQueueItem(index: Int) {
        executeWhenReady { controller ->
            controller.seekTo(index, 0L)
            controller.play()
        }
    }

    fun removeFromQueue(index: Int) {
        executeWhenReady { controller ->
            controller.removeMediaItem(index)
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
