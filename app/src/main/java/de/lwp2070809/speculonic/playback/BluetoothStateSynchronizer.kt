@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class BluetoothStateSynchronizer(
    private val context: Context,
    private val deviceDetector: BluetoothCarDeviceDetector,
    private val serviceScope: CoroutineScope,
    private val mediaSessionProvider: () -> MediaSession?,
    private val setJitterProtected: (Boolean) -> Unit = {}
) {
    var syncPlaybackState = true

    private var lastAudioStateCheckTime = 0L
    private var lastIsInCall = false
    private var lastIsOtherMusicActive = false

    
    fun syncPlaybackStateToBluetooth() {
        if (!deviceDetector.carBluetoothEnabled || !deviceDetector.isCarBluetoothConnected()) {
            LogManager.d("BluetoothStateSynchronizer: syncPlaybackStateToBluetooth skipped, car Bluetooth connection not recognized or car feature not enabled")
            return
        }

        val session = mediaSessionProvider() ?: return
        val player = session.player
        
        try {
            
            if (player.playWhenReady) {
                LogManager.d("BluetoothStateSynchronizer: Already playing, skipping Play-Pause wake-up.")
                return
            }

            val currentTime = System.currentTimeMillis()
            var isInCall = lastIsInCall
            var isOtherMusicActive = lastIsOtherMusicActive
            
            if (currentTime - lastAudioStateCheckTime > 1000L) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    val mode = audioManager.mode
                    isInCall = mode == AudioManager.MODE_IN_CALL || 
                                   mode == AudioManager.MODE_IN_COMMUNICATION || 
                                   mode == AudioManager.MODE_RINGTONE
                    isOtherMusicActive = audioManager.isMusicActive
                    
                    lastIsInCall = isInCall
                    lastIsOtherMusicActive = isOtherMusicActive
                    lastAudioStateCheckTime = currentTime
                }
            }

            if (isInCall || isOtherMusicActive) {
                LogManager.w("BluetoothStateSynchronizer: Call state active ($isInCall) or other active media application ($isOtherMusicActive) detected. Skipping Play-Pause state sync to prevent aggressive focus preemption.")
                return
            }

            
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                LogManager.d("BluetoothStateSynchronizer: Player is STATE_IDLE, skipping state synchronization.")
                return
            }

            
            
            val originalVolume = player.volume
            player.volume = 0f
            
            setJitterProtected(true)
            player.play()
            serviceScope.launch {
                try {
                    delay(100)
                } finally {
                    try {
                        try {
                            if (player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                                player.pause()
                            }
                        } catch (e: Exception) {
                            LogManager.w("BluetoothStateSynchronizer: Exception occurred when resuming pause state, player might have been released", e)
                        } finally {
                            player.volume = originalVolume
                            LogManager.i("BluetoothStateSynchronizer: Successfully triggered silent Play-Pause jitter wake-up, MediaSession activated.")
                        }
                    } finally {
                        launch {
                            delay(150)
                            setJitterProtected(false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("BluetoothStateSynchronizer: Failed to trigger silent Play-Pause jitter wake-up", e)
        }
    }
}
