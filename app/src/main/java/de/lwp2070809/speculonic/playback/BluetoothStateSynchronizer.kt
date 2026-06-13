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
    private val mediaSessionProvider: () -> MediaSession?
) {
    var syncPlaybackState = true

    private var lastAudioStateCheckTime = 0L
    private var lastIsInCall = false
    private var lastIsOtherMusicActive = false

    
    fun syncPlaybackStateToBluetooth() {
        if (!deviceDetector.carBluetoothEnabled || !deviceDetector.isCarBluetoothConnected()) {
            LogManager.d("BluetoothStateSynchronizer: syncPlaybackStateToBluetooth 跳过，未识别到车载蓝牙连接或车载功能未启用")
            return
        }

        val session = mediaSessionProvider() ?: return
        val player = session.player
        
        try {
            
            if (player.playWhenReady) {
                LogManager.d("BluetoothStateSynchronizer: 当前已在播放状态，跳过 Play-Pause 唤醒。")
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
                LogManager.w("BluetoothStateSynchronizer: 检测到当前处于通话状态($isInCall)或其它音视频应用正在活跃播放($isOtherMusicActive)。为了防止粗暴抢占焦点，跳过 Play-Pause 状态同步。")
                return
            }

            
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                LogManager.d("BluetoothStateSynchronizer: 播放器当前处于 STATE_IDLE，跳过状态同步。")
                return
            }

            
            
            val originalVolume = player.volume
            player.volume = 0f
            
            player.play()
            serviceScope.launch {
                try {
                    delay(100)
                } finally {
                    try {
                        if (player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                            player.pause()
                            player.volume = originalVolume
                            LogManager.i("BluetoothStateSynchronizer: 成功触发无声 Play-Pause 抖动唤醒，MediaSession 已激活并完美同步为 STATE_PAUSED。")
                        }
                    } catch (e: Exception) {
                        LogManager.w("BluetoothStateSynchronizer: 恢复暂停状态时发生异常，播放器可能已被释放", e)
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("BluetoothStateSynchronizer: 触发静音级 Play-Pause 抖动唤醒失败", e)
        }
    }
}
