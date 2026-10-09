package de.lwp2070809.speculonic.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackAudioFocusHelper(
    context: Context,
    private val volumeCoordinator: VolumeCoordinator? = null,
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
    private var preDuckVolume = 1.0f
    private var isDucking = false
    private val focusLock = Any()

    fun resetLossState() {
        synchronized(focusLock) {
            isTransientLossActive = false
            playWhenReadyBeforeLoss = false
            isDucking = false
        }
        volumeCoordinator?.setDucking(false)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isDefaultFocusHandling) return@OnAudioFocusChangeListener
        val player = getPlayer() ?: return@OnAudioFocusChangeListener
        val realPlayer = if (player is BluetoothCarManager.CarDisguisePlayer) {
            player.wrappedPlayer
        } else {
            player
        }

        val changeStr = when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            else -> "FOCUS_$focusChange"
        }
        LogManager.i(LogTag.PLAYBACK, "AudioFocus change received -> $changeStr")

        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (pauseOnAudioFocusLoss) {
                    synchronized(focusLock) {
                        playWhenReadyBeforeLoss = false
                        isTransientLossActive = false
                        isDucking = false
                    }
                    volumeCoordinator?.setDucking(false)
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
                    synchronized(focusLock) {
                        if (!isDucking) {
                            preDuckVolume = realPlayer.volume
                            isDucking = true
                        }
                    }
                    if (volumeCoordinator != null) {
                        volumeCoordinator.setDucking(true, 0.2f)
                    } else {
                        realPlayer.volume = preDuckVolume * 0.2f
                    }
                } else {
                    synchronized(focusLock) {
                        playWhenReadyBeforeLoss = realPlayer.playWhenReady
                        isTransientLossActive = true
                    }
                    realPlayer.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                var shouldPlay = false
                synchronized(focusLock) {
                    if (isDucking) {
                        if (volumeCoordinator != null) {
                            volumeCoordinator.setDucking(false)
                        } else {
                            realPlayer.volume = preDuckVolume
                        }
                        isDucking = false
                    }
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
        val result = audioManager.requestAudioFocus(focusRequest!!)
        val resultStr = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED($result)"
        LogManager.i(LogTag.PLAYBACK, "AudioFocus requested -> $resultStr")
        return result
    }

    fun abandonAudioFocus() {
        focusRequest?.let {
            val result = audioManager.abandonAudioFocusRequest(it)
            LogManager.i(LogTag.PLAYBACK, "AudioFocus abandoned (result=$result)")
        }
    }
}
