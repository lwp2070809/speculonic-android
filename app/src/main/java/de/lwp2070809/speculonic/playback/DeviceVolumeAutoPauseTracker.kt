package de.lwp2070809.speculonic.playback

/**
 * 设备媒体音量归零自动暂停状态跟踪器。
 * 仅在播放器处于活跃播放 intention 且音量从非 0 / 未静音跃迁至 0 / 静音时建议暂停；
 * 跳过首次异步初始化回调，并在开关关闭期间持续维护基线状态。
 */
class DeviceVolumeAutoPauseTracker {
    private var lastIsZeroOrMuted: Boolean? = null

    fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
        enabled: Boolean,
        isActivelyPlaying: Boolean
    ): Boolean {
        val currentIsZeroOrMuted = volume <= 0 || muted
        val previous = lastIsZeroOrMuted
        lastIsZeroOrMuted = currentIsZeroOrMuted

        // 首次回调来自 StreamVolumeManager 异步初始化，仅用于建立基线
        if (previous == null) return false

        return enabled && isActivelyPlaying && !previous && currentIsZeroOrMuted
    }

    fun reset() {
        lastIsZeroOrMuted = null
    }
}
