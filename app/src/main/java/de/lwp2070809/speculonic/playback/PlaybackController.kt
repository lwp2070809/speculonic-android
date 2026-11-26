package de.lwp2070809.speculonic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater
import de.lwp2070809.speculonic.util.CoverArtKeyUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

internal fun Player.showsPauseButton() = playWhenReady &&
    (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)

internal fun Player.toggleCurrentItem() {
    if (showsPauseButton()) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekTo(0L)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
}

data class PlaybackState(
    val currentSongId: String = "",
    val currentSongTitle: String = "",
    val currentArtist: String = "",
    val artworkUri: Uri? = null,
    val artworkId: String? = null,
    val isPlaying: Boolean = false,
    val showPauseButton: Boolean = false,
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

private data class WidgetStateSnapshot(
    val songId: String,
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val isPlaying: Boolean,
    val showPauseButton: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext context: Context,
    private val preferencesManager: PreferencesManager,
    private val database: AppDatabase,
    private val repository: dagger.Lazy<SubsonicRepository>
) {
    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val musicDao = database.musicDao()
    
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

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _isSessionReady = MutableStateFlow(false)
    val isSessionReady = _isSessionReady.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var sleepTimerDeadlineRealtime: Long = 0L
    
    private var isAppVisible = true
    private val pendingActions = java.util.concurrent.CopyOnWriteArrayList<(MediaController) -> Unit>()

    init {
        scope.launch {
            playbackState
                .map { state ->
                    WidgetStateSnapshot(
                        songId = state.currentSongId,
                        title = state.currentSongTitle,
                        artist = state.currentArtist,
                        artworkUri = state.artworkUri,
                        isPlaying = state.isPlaying,
                        showPauseButton = state.showPauseButton
                    )
                }
                .distinctUntilChanged()
                .collect {
                SpeculonicWidgetUpdater.enqueueUpdate(appContext)
            }
        }
    }

    private fun executeWhenReady(action: (MediaController) -> Unit) {
        ensureController()
        val ctrl = controller
        if (ctrl != null && _isSessionReady.value) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                action(ctrl)
            } else {
                scope.launch { action(ctrl) }
            }
        } else {
            LogManager.i("PlaybackController: Session not ready (ctrl=${ctrl != null}, ready=${_isSessionReady.value}), queueing action")
            pendingActions.add(action)
        }
    }

    private fun flushPendingActionsIfReady(ctrl: MediaController) {
        if (!_isSessionReady.value || pendingActions.isEmpty()) return
        val actionsToRun = pendingActions.toList()
        pendingActions.clear()
        LogManager.i("PlaybackController: Executing ${actionsToRun.size} pending actions")
        actionsToRun.forEach { it(ctrl) }
    }

    /**
     * 供微件 ActionCallback 或后台任务调用的异步安全执行，挂起至 MediaController 就绪且 PlaybackService 真实 Player 与队列恢复完毕后在主线程执行
     */
    suspend fun executeOnControllerAsync(action: (MediaController) -> Unit) {
        withContext(Dispatchers.Main) {
            ensureController()
            val ctrl = controller
            if (ctrl != null && _isSessionReady.value) {
                action(ctrl)
                return@withContext
            }

            val readyCtrl = try {
                kotlinx.coroutines.withTimeoutOrNull(CONTROLLER_READY_TIMEOUT_MS) {
                    val resolvedController = ctrl ?: awaitControllerInstance() ?: return@withTimeoutOrNull null
                    val readyFromExtras = resolvedController.sessionExtras.getBoolean(
                        PlaybackService.EXTRA_KEY_SESSION_READY,
                        false
                    )
                    if (_isSessionReady.value || readyFromExtras) {
                        _isSessionReady.value = true
                        resolvedController
                    } else {
                        _isSessionReady.first { it }
                        resolvedController
                    }
                }
            } catch (e: Exception) {
                LogManager.w("PlaybackController: Error awaiting ready controller: ${e.message}")
                null
            }

            if (readyCtrl != null) {
                action(readyCtrl)
            } else {
                LogManager.w("PlaybackController: Timed out awaiting session ready, aborting action to prevent memory leak")
            }
        }
    }

    private suspend fun awaitControllerInstance(): MediaController? {
        val future = controllerFuture ?: return null
        if (future.isDone) {
            return try {
                future.get()
            } catch (e: Exception) {
                LogManager.w("PlaybackController: Controller future completed with error", e)
                null
            }
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            future.addListener({
                try {
                    val resolved = if (future.isDone) future.get() else null
                    if (continuation.isActive) {
                        continuation.resume(resolved)
                    }
                } catch (e: Exception) {
                    LogManager.w("PlaybackController: Controller future listener failed", e)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
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
                LogManager.w("PlaybackController: Current controller future is in error state, re-initializing...", e)
                val failedFuture = controllerFuture
                controllerFuture = null
                failedFuture?.let { MediaController.releaseFuture(it) }
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
                    val ready = extras.getBoolean(PlaybackService.EXTRA_KEY_SESSION_READY, false)
                    _isSessionReady.value = ready
                    if (ready) {
                        flushPendingActionsIfReady(controller)
                    }
                    updateState()
                }

                override fun onDisconnected(controller: MediaController) {
                    super.onDisconnected(controller)
                    _isSessionReady.value = false
                    _playbackState.value = _playbackState.value.copy(isPlaying = false, showPauseButton = false)
                    handleProgressTicker()
                    LogManager.w("PlaybackController: MediaController disconnected")
                }
            })
            .buildAsync()
        controllerFuture = newFuture
        newFuture.addListener({
            try {
                setupController()
            } catch (e: Throwable) {
                LogManager.e("PlaybackController: Failed to setup controller after connection", e)
                MediaController.releaseFuture(newFuture)
                controllerFuture = null
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(appContext))
    }

    private fun setupController() {
        val controller = controller ?: return
        _isSessionReady.value = controller.sessionExtras.getBoolean(PlaybackService.EXTRA_KEY_SESSION_READY, false)
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

        flushPendingActionsIfReady(controller)
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
                    delay(PROGRESS_TICK_INTERVAL_MS)
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
        val pos = controller.currentPosition
        _currentPosition.value = pos

        val currentState = _playbackState.value
        val isTimerRunning = currentState.isSleepTimerRunning &&
            currentState.sleepTimerMode == SleepTimerMode.TIME &&
            sleepTimerDeadlineRealtime > 0L
        val remainingMillis = if (isTimerRunning) {
            (sleepTimerDeadlineRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            currentState.sleepTimerRemainingMillis
        }

        val timerChanged = kotlin.math.abs(remainingMillis - currentState.sleepTimerRemainingMillis) >= 1000L
        val effectiveDur = getEffectiveDuration(controller)
        val durationChanged = effectiveDur > 0L && effectiveDur != currentState.duration
        if (timerChanged || durationChanged) {
            _playbackState.value = currentState.copy(
                currentPosition = pos,
                duration = if (durationChanged) effectiveDur else currentState.duration,
                sleepTimerRemainingMillis = remainingMillis
            )
        }
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
        val timerMode = SleepTimerMode.fromName(timerModeStr)
        if (timerMode == SleepTimerMode.TIME && deadlineRealtime > 0L) {
            sleepTimerDeadlineRealtime = deadlineRealtime
        } else {
            sleepTimerDeadlineRealtime = 0L
        }
        val remainingMillis = if (timerMode == SleepTimerMode.TIME && sleepTimerDeadlineRealtime > 0L) {
            (sleepTimerDeadlineRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } else {
            sessionExtras.getLong("sleepTimerRemainingMillis", 0L)
        }

        val pos = controller.currentPosition
        _currentPosition.value = pos

        _playbackState.value = _playbackState.value.copy(
            currentSongId = currentMediaItem?.mediaId ?: "",
            currentSongTitle = realTitle,
            currentArtist = realArtist,
            artworkUri = CoverArtKeyUtils.stripVolatileParams(currentMediaItem?.mediaMetadata?.artworkUri),
            artworkId = artworkId,
            isPlaying = controller.isPlaying,
            showPauseButton = controller.showsPauseButton(),
            currentPosition = pos,
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

    fun play(mediaItems: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean? = null, queueTitle: String? = null) {
        _playbackState.value = _playbackState.value.copy(queueTitle = queueTitle)
        executeWhenReady { controller ->
            scope.launch {
                val bundle = android.os.Bundle().apply { putString("queueTitle", queueTitle) }
                controller.sendCustomCommand(
                    androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY),
                    bundle
                )

                when (shuffle) {
                    true -> {
                        // 显式指定随机播放：进入随机播放模式，强制重置 repeatMode 为 REPEAT_MODE_ALL，杜绝单曲循环冲突
                        controller.repeatMode = Player.REPEAT_MODE_ALL
                        controller.shuffleModeEnabled = true
                    }
                    false -> {
                        // 显式指定顺序播放全部：进入列表顺序循环模式，关闭随机
                        controller.repeatMode = Player.REPEAT_MODE_ALL
                        controller.shuffleModeEnabled = false
                    }
                    null -> {
                        // 未显式指定（如点击单曲项）：按需读取并保持现有的播放模式
                        val isReady = controller.playbackState != Player.STATE_IDLE
                        val effectiveShuffleMode = if (isReady) {
                            controller.shuffleModeEnabled
                        } else {
                            preferencesManager.shuffleMode.first()
                        }
                        val effectiveRepeatMode = if (effectiveShuffleMode) {
                            Player.REPEAT_MODE_ALL
                        } else {
                            val rawRepeatMode = if (isReady && controller.repeatMode != Player.REPEAT_MODE_OFF) {
                                controller.repeatMode
                            } else {
                                preferencesManager.repeatMode.first()
                            }
                            if (rawRepeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else rawRepeatMode
                        }
                        controller.repeatMode = effectiveRepeatMode
                        controller.shuffleModeEnabled = effectiveShuffleMode
                    }
                }
                
                controller.setMediaItems(mediaItems, startIndex, 0L)
                controller.prepare()
                controller.play()
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
            if (controller.mediaItemCount > 0) controller.toggleCurrentItem()
            else scope.launch { restoreQueueAndPlay() }
        }
    }

    /**
     * 恢复完整持久化播放队列并开始播放，仅当队列历史为空时回退恢复最后一首单曲。
     */
    suspend fun restoreQueueAndPlay(playWhenReady: Boolean = true): Boolean {
        return try {
            val loadedData = loadRestorePayload() ?: return false
            var restored = false
            executeOnControllerAsync { ctrl ->
                if (ctrl.mediaItemCount == 0) {
                    val titleBundle = android.os.Bundle().apply { putString("queueTitle", loadedData.queueTitle) }
                    ctrl.sendCustomCommand(
                        androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY),
                        titleBundle
                    )
                    ctrl.repeatMode = loadedData.repeatMode
                    ctrl.shuffleModeEnabled = loadedData.shuffleMode
                    ctrl.setMediaItems(loadedData.mediaItems, loadedData.startIndex, loadedData.startPosition)
                    ctrl.prepare()
                }
                if (playWhenReady) {
                    ctrl.play()
                }
                restored = true
            }
            restored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.w("PlaybackController: restoreQueueAndPlay failed: ${e.message}", e)
            false
        }
    }

    private suspend fun loadRestorePayload(): RestorePayload? = withContext(Dispatchers.IO) {
        val repo = repository.get()
        repo.ensureConfigured()

        val savedQueue = musicDao.getPlaybackQueue()
        val lastSongId = preferencesManager.lastSongId.first() ?: ""
        val mediaItems = resolveRestoredMediaItems(repo, savedQueue, lastSongId)
        if (mediaItems.isEmpty()) return@withContext null

        val savedIndex = preferencesManager.lastQueueIndex.first()
        val lastPosition = preferencesManager.lastPosition.first()
        val lastQueueTitle = preferencesManager.lastQueueTitle.first()
        val repeatMode = preferencesManager.repeatMode.first()
        val shuffleMode = preferencesManager.shuffleMode.first()
        val effectiveRepeat = if (shuffleMode && repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_ALL
        } else {
            repeatMode
        }

        val effectiveIndex = resolveEffectiveIndex(mediaItems, savedIndex, lastSongId)
        val effectivePosition = if (mediaItems.getOrNull(effectiveIndex)?.mediaId == lastSongId) {
            lastPosition
        } else {
            0L
        }
        RestorePayload(
            mediaItems = mediaItems,
            startIndex = effectiveIndex,
            startPosition = effectivePosition,
            queueTitle = lastQueueTitle,
            repeatMode = effectiveRepeat,
            shuffleMode = shuffleMode
        )
    }

    private suspend fun resolveRestoredMediaItems(
        repo: SubsonicRepository,
        savedQueue: List<de.lwp2070809.speculonic.data.db.entities.SongEntity>,
        lastSongId: String
    ): List<MediaItem> {
        if (savedQueue.isNotEmpty()) {
            return savedQueue.map { it.toMediaItem(repo) }
        }
        if (lastSongId.isNotEmpty()) {
            return musicDao.getSongById(lastSongId)?.let { listOf(it.toMediaItem(repo)) } ?: emptyList()
        }
        return emptyList()
    }

    private fun resolveEffectiveIndex(
        mediaItems: List<MediaItem>,
        savedIndex: Int,
        lastSongId: String
    ): Int {
        if (savedIndex in mediaItems.indices && mediaItems[savedIndex].mediaId == lastSongId) {
            return savedIndex
        }
        val matchedIndex = mediaItems.indexOfFirst { it.mediaId == lastSongId }
        return if (matchedIndex != -1) matchedIndex else savedIndex.coerceIn(0, mediaItems.size - 1)
    }

    private data class RestorePayload(
        val mediaItems: List<MediaItem>,
        val startIndex: Int,
        val startPosition: Long,
        val queueTitle: String?,
        val repeatMode: Int,
        val shuffleMode: Boolean
    )

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
                !controller.shuffleModeEnabled &&
                    (controller.repeatMode == Player.REPEAT_MODE_ALL || controller.repeatMode == Player.REPEAT_MODE_OFF) -> {
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
                if (controller.mediaItemCount == 1) {
                    controller.stop()
                    controller.clearMediaItems()
                } else {
                    controller.removeMediaItem(index)
                }
            }
        }
    }

    private companion object {
        const val CONTROLLER_READY_TIMEOUT_MS = 5000L
        const val PROGRESS_TICK_INTERVAL_MS = 250L
    }
}
