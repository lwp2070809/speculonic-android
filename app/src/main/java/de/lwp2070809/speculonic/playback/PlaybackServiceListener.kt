package de.lwp2070809.speculonic.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag

data class PlaybackServiceDependencies(
    val errorHandler: PlaybackErrorHandler,
    val persistence: PlaybackStatePersistence,
    val carAudioManager: BluetoothCarManager,
    val audioFocusHelper: PlaybackAudioFocusHelper
)

data class PlaybackServiceCallbacks(
    val onTriggerSilentCache: (MediaItem) -> Unit,
    val onMediaItemTransitionForTimer: ((MediaItem?, Int) -> Unit)? = null,
    val onTimelineChangedForTimer: (() -> Unit)? = null,
    val onPlaybackEndedForTimer: (() -> Unit)? = null,
    val isPauseOnVolumeZeroEnabled: () -> Boolean = { false }
)

class PlaybackServiceListener(
    private val player: Player,
    private val deps: PlaybackServiceDependencies,
    private val callbacks: PlaybackServiceCallbacks,
    private val context: Context? = null
) : Player.Listener {

    private var currentActiveMediaId: String? = player.currentMediaItem?.mediaId
    private val volumeAutoPauseTracker = DeviceVolumeAutoPauseTracker(resolveInitialZeroOrMuted(context))

    fun publishWidgetState() {
        val isIntended = player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE
        PlaybackService.isPlaybackIntended = isIntended

        val appContext = context ?: return
        val mediaItem = player.currentMediaItem
        val extras = mediaItem?.mediaMetadata?.extras
        val title = extras?.getString("realTitle")
            ?: mediaItem?.mediaMetadata?.title?.toString()
            ?: ""
        val artist = extras?.getString("realArtist")
            ?: mediaItem?.mediaMetadata?.artist?.toString()
            ?: ""
        val artworkUri = mediaItem?.mediaMetadata?.artworkUri?.toString() ?: ""
        SpeculonicWidgetUpdater.updateFromService(
            context = appContext,
            songId = mediaItem?.mediaId ?: "",
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            isPlaying = isIntended
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        deps.errorHandler.handlePlayerError(player, error)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        callbacks.onMediaItemTransitionForTimer?.invoke(mediaItem, reason)
        val reasonStr = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "REASON_$reason"
        }
        val title = mediaItem?.mediaMetadata?.title ?: "Unknown"
        val artist = mediaItem?.mediaMetadata?.artist ?: "Unknown"
        LogManager.i(
            LogTag.PLAYBACK,
            "MediaItem transition -> id=${mediaItem?.mediaId}, title='$title', artist='$artist', reason=$reasonStr"
        )

        if (mediaItem == null) {
            currentActiveMediaId = null
            publishWidgetState()
            deps.persistence.savePlaybackState(player, immediate = true)
            return
        }

        val isSameAsPrevious = mediaItem.mediaId == currentActiveMediaId
        val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

        if (isSameAsPrevious && !isRepeat) {
            publishWidgetState()
            deps.persistence.savePlaybackState(player, immediate = true)
            return
        }

        currentActiveMediaId = mediaItem.mediaId

        callbacks.onTriggerSilentCache(mediaItem)
        val carManager = deps.carAudioManager
        if (carManager.carBluetoothEnabled &&
            carManager.bluetoothLyricsEnabled &&
            carManager.isCarBluetoothConnected()
        ) {
            carManager.loadLyricsForBluetooth(mediaItem)
        }
        publishWidgetState()
        deps.persistence.savePlaybackState(player, immediate = true)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            LogManager.i(LogTag.PLAYBACK, "Timeline playlist changed (windowCount=${timeline.windowCount})")
            callbacks.onTimelineChangedForTimer?.invoke()
            deps.persistence.savePlaybackQueue(player)
            deps.persistence.savePlaybackState(player)
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        val modeStr = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> "OFF"
            Player.REPEAT_MODE_ONE -> "ONE"
            Player.REPEAT_MODE_ALL -> "ALL"
            else -> "$repeatMode"
        }
        LogManager.i(LogTag.PLAYBACK, "Repeat mode changed -> $modeStr")
        deps.persistence.savePlaybackState(player, immediate = true)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        LogManager.i(LogTag.PLAYBACK, "Shuffle mode changed -> $shuffleModeEnabled")
        deps.persistence.savePlaybackState(player, immediate = true)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateName = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($playbackState)"
        }
        LogManager.i(LogTag.PLAYBACK, "Playback state changed -> $stateName (playWhenReady=${player.playWhenReady})")

        if (playbackState == Player.STATE_READY) {
            deps.errorHandler.resetErrorCount()
        }
        if (playbackState == Player.STATE_ENDED) {
            callbacks.onPlaybackEndedForTimer?.invoke()
            publishWidgetState()
        } else if (playbackState == Player.STATE_IDLE ||
            playbackState == Player.STATE_READY ||
            playbackState == Player.STATE_BUFFERING
        ) {
            publishWidgetState()
        }
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            deps.audioFocusHelper.releaseManualFocus()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PlaybackService.isPlayerPlaying = isPlaying
        publishWidgetState()
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        val isActivelyPlaying =
            player.playWhenReady &&
                (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (volumeAutoPauseTracker.onDeviceVolumeChanged(
                volume = volume,
                muted = muted,
                enabled = callbacks.isPauseOnVolumeZeroEnabled(),
                isActivelyPlaying = isActivelyPlaying
            )
        ) {
            LogManager.i(
                LogTag.PLAYBACK,
                "Device volume dropped to zero (volume=$volume, muted=$muted); auto-pausing playback."
            )
            player.pause()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val reasonStr = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
            else -> "REASON_$reason"
        }
        LogManager.i(LogTag.PLAYBACK, "playWhenReady changed -> $playWhenReady (reason=$reasonStr)")
        publishWidgetState()

        if (playWhenReady) {
            handlePlayWhenReadyResumed()
        } else {
            handlePlayWhenReadyPaused()
        }
    }

    private fun handlePlayWhenReadyPaused() {
        deps.persistence.savePlaybackState(player, immediate = true)
        deps.persistence.stopPositionPersistence()
        deps.carAudioManager.stopBluetoothLyricsUpdate()
        val focusHelper = deps.audioFocusHelper
        if (!focusHelper.isDefaultFocusHandling &&
            !focusHelper.isTransientLossActive &&
            !focusHelper.isDelayedFocusPending
        ) {
            focusHelper.abandonAudioFocus()
        }
    }

    private fun handlePlayWhenReadyResumed() {
        deps.persistence.startPositionPersistence(player)
        player.currentMediaItem?.let { callbacks.onTriggerSilentCache(it) }
        val carManager = deps.carAudioManager
        if (carManager.carBluetoothEnabled && carManager.bluetoothLyricsEnabled) {
            carManager.startBluetoothLyricsUpdate()
        }
        val focusHelper = deps.audioFocusHelper
        if (focusHelper.isDefaultFocusHandling) return

        when (focusHelper.requestAudioFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                focusHelper.setDelayedFocusPending(true)
                player.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                focusHelper.setDelayedFocusPending(false)
                focusHelper.resetLossState()
            }
            else -> {
                focusHelper.setDelayedFocusPending(false)
                player.playWhenReady = false
            }
        }
    }

    private companion object {
        private fun resolveInitialZeroOrMuted(context: Context?): Boolean? {
            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            return volume <= 0 || muted
        }
    }
}
