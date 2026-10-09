package de.lwp2070809.speculonic.playback

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag

class PlaybackServiceListener(
    private val player: Player,
    private val errorHandler: PlaybackErrorHandler,
    private val persistence: PlaybackStatePersistence,
    private val carAudioManager: BluetoothCarManager,
    private val audioFocusHelper: PlaybackAudioFocusHelper,
    private val onTriggerSilentCache: (MediaItem) -> Unit,
    private val onMediaItemTransitionForTimer: ((MediaItem?, Int) -> Unit)? = null
) : Player.Listener {

    private var currentActiveMediaId: String? = player.currentMediaItem?.mediaId

    override fun onPlayerError(error: PlaybackException) {
        errorHandler.handlePlayerError(player, error)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        onMediaItemTransitionForTimer?.invoke(mediaItem, reason)
        val reasonStr = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "REASON_$reason"
        }
        val title = mediaItem?.mediaMetadata?.title ?: "Unknown"
        val artist = mediaItem?.mediaMetadata?.artist ?: "Unknown"
        LogManager.i(LogTag.PLAYBACK, "MediaItem transition -> id=${mediaItem?.mediaId}, title='$title', artist='$artist', reason=$reasonStr")

        if (mediaItem == null) {
            persistence.savePlaybackState(player)
            return
        }

        val isSameAsPrevious = mediaItem.mediaId == currentActiveMediaId
        val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT

        if (isSameAsPrevious && !isRepeat) {
            persistence.savePlaybackState(player)
            return
        }

        currentActiveMediaId = mediaItem.mediaId

        onTriggerSilentCache(mediaItem)
        if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled && carAudioManager.isCarBluetoothConnected()) {
            carAudioManager.loadLyricsForBluetooth(mediaItem)
        }
        persistence.savePlaybackState(player)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            LogManager.i(LogTag.PLAYBACK, "Timeline playlist changed (windowCount=${timeline.windowCount})")
            persistence.savePlaybackQueue(player)
            persistence.savePlaybackState(player)
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
        persistence.savePlaybackState(player)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        LogManager.i(LogTag.PLAYBACK, "Shuffle mode changed -> $shuffleModeEnabled")
        persistence.savePlaybackState(player)
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
            errorHandler.resetErrorCount()
        }
        if (!audioFocusHelper.isDefaultFocusHandling) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                audioFocusHelper.abandonAudioFocus()
                audioFocusHelper.resetLossState()
            }
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

        if (!playWhenReady) {
            persistence.savePlaybackState(player, immediate = true)
            persistence.stopPositionPersistence()
            carAudioManager.stopBluetoothLyricsUpdate()
            if (!audioFocusHelper.isDefaultFocusHandling) {
                if (!audioFocusHelper.isTransientLossActive) {
                    audioFocusHelper.abandonAudioFocus()
                }
            }
        } else {
            persistence.startPositionPersistence(player)
            if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                carAudioManager.startBluetoothLyricsUpdate()
            }
            if (!audioFocusHelper.isDefaultFocusHandling) {
                val result = audioFocusHelper.requestAudioFocus()
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player.playWhenReady = false
                } else {
                    audioFocusHelper.resetLossState()
                }
            }
        }
    }
}
