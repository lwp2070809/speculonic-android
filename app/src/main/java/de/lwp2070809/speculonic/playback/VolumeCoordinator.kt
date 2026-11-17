package de.lwp2070809.speculonic.playback

import androidx.media3.common.Player
import de.lwp2070809.speculonic.util.LogManager

/**
 * 统一音量协调器，统筹音频焦点临时让步（Ducking）与基础音量状态。
 */
class VolumeCoordinator {

    private val lock = Any()
    private var activePlayer: Player? = null

    private var baseVolume: Float = 1.0f
    private var duckMultiplier: Float = 1.0f

    fun setPlayer(player: Player?) {
        synchronized(lock) {
            activePlayer = player
            applyVolumeLocked()
        }
    }

    fun setBaseVolume(volume: Float) {
        synchronized(lock) {
            baseVolume = volume.coerceIn(0.0f, 1.0f)
            applyVolumeLocked()
        }
    }

    fun getBaseVolume(): Float {
        synchronized(lock) {
            return baseVolume
        }
    }

    fun setDucking(isDucking: Boolean, duckFactor: Float = 0.2f) {
        synchronized(lock) {
            duckMultiplier = if (isDucking) duckFactor.coerceIn(0.0f, 1.0f) else 1.0f
            applyVolumeLocked()
        }
    }

    private fun applyVolumeLocked() {
        val player = activePlayer ?: return
        val targetVolume = (baseVolume * duckMultiplier).coerceIn(0.0f, 1.0f)
        try {
            player.volume = targetVolume
            LogManager.d("VolumeCoordinator: Applied volume $targetVolume (base=$baseVolume, duck=$duckMultiplier)")
        } catch (e: Exception) {
            LogManager.w("VolumeCoordinator: Failed to apply volume to player", e)
        }
    }
}
