@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 车载蓝牙 MediaSession 元数据纯事件同步器。
 * 彻底移除旧版 Play-Pause 静音抖动逻辑与冗余状态合成回调，杜绝误触发内部监听、焦点抢占以及状态失步。
 */
class BluetoothStateSynchronizer(
    private val deviceDetector: BluetoothCarDeviceDetector,
    private val serviceScope: CoroutineScope,
    private val mediaSessionProvider: () -> MediaSession?
) {
    var syncPlaybackState = true

    fun syncPlaybackStateToBluetooth() {
        if (!syncPlaybackState || !deviceDetector.carBluetoothEnabled || !deviceDetector.isCarBluetoothConnected()) {
            LogManager.d(
                "BluetoothStateSynchronizer: syncPlaybackStateToBluetooth skipped, " +
                    "car Bluetooth connection not recognized or feature disabled"
            )
            return
        }

        serviceScope.launch(Dispatchers.Main.immediate) {
            val session = mediaSessionProvider() ?: return@launch
            val player = session.player
            if (player.playbackState == Player.STATE_IDLE) {
                LogManager.d("BluetoothStateSynchronizer: Player is STATE_IDLE, skipping metadata synchronization.")
                return@launch
            }
            (player as? BluetoothCarManager.CarDisguisePlayer)?.forceStateSync()
            LogManager.i("BluetoothStateSynchronizer: Synced MediaSession metadata to car Bluetooth.")
        }
    }
}
