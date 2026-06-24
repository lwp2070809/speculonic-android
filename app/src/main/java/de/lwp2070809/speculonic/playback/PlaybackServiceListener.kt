package de.lwp2070809.speculonic.playback

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline

class PlaybackServiceListener(
    private val player: Player,
    private val errorHandler: PlaybackErrorHandler,
    private val persistence: PlaybackStatePersistence,
    private val carAudioManager: BluetoothCarManager,
    private val audioFocusHelper: PlaybackAudioFocusHelper,
    private val onTriggerSilentCache: (MediaItem) -> Unit
) : Player.Listener {

    private var currentActiveMediaId: String? = player.currentMediaItem?.mediaId

    override fun onPlayerError(error: PlaybackException) {
        errorHandler.handlePlayerError(player, error)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            persistence.savePlaybackQueue(player)
            persistence.savePlaybackState(player)
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) = persistence.savePlaybackState(player)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persistence.savePlaybackState(player)

    override fun onPlaybackStateChanged(playbackState: Int) {
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
