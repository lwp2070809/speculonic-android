package de.lwp2070809.speculonic.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class PlaybackAudioFocusHelper(
    context: Context,
    private val getPlayer: () -> Player?
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var duckOnTransientFocusLoss = true
    var pauseOnAudioFocusLoss = true
    var isDefaultFocusHandling = true

    private var focusRequest: AudioFocusRequest? = null
    var playWhenReadyBeforeLoss = false
        private set
    var isTransientLossActive = false
        private set
    private val focusLock = Any()

    fun resetLossState() {
        synchronized(focusLock) {
            isTransientLossActive = false
            playWhenReadyBeforeLoss = false
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isDefaultFocusHandling) return@OnAudioFocusChangeListener
        val player = getPlayer() ?: return@OnAudioFocusChangeListener
        val realPlayer = if (player is BluetoothCarManager.CarDisguisePlayer) {
            player.wrappedPlayer
        } else {
            player
        }
        if (realPlayer is ExoPlayer) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (pauseOnAudioFocusLoss) {
                        synchronized(focusLock) {
                            playWhenReadyBeforeLoss = false
                            isTransientLossActive = false
                        }
                        realPlayer.pause()
                        abandonAudioFocus()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    synchronized(focusLock) {
                        playWhenReadyBeforeLoss = realPlayer.playWhenReady
                        isTransientLossActive = true
                    }
                    realPlayer.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (duckOnTransientFocusLoss) {
                        realPlayer.volume = 0.2f
                    } else {
                        synchronized(focusLock) {
                            playWhenReadyBeforeLoss = realPlayer.playWhenReady
                            isTransientLossActive = true
                        }
                        realPlayer.pause()
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    realPlayer.volume = 1.0f
                    var shouldPlay = false
                    synchronized(focusLock) {
                        if (isTransientLossActive) {
                            isTransientLossActive = false
                            if (playWhenReadyBeforeLoss) {
                                shouldPlay = true
                                playWhenReadyBeforeLoss = false
                            }
                        }
                    }
                    if (shouldPlay) {
                        realPlayer.play()
                    }
                }
            }
        }
    }

    fun requestAudioFocus(): Int {
        if (focusRequest == null) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }
        return audioManager.requestAudioFocus(focusRequest!!)
    }

    fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }
}
