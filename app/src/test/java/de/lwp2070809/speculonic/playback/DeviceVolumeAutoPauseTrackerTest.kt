package de.lwp2070809.speculonic.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVolumeAutoPauseTrackerTest {

    @Test
    fun `initial zero or muted callback only establishes baseline and does not pause`() {
        val tracker = DeviceVolumeAutoPauseTracker()

        assertFalse(
            tracker.onDeviceVolumeChanged(
                volume = 0,
                muted = true,
                enabled = true,
                isActivelyPlaying = true
            )
        )
    }

    @Test
    fun `dropping from positive volume to zero while playing pauses playback`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 5, muted = false, enabled = true, isActivelyPlaying = true))

        assertTrue(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = true))
    }

    @Test
    fun `muting from unmuted non-zero volume while playing pauses playback`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 5, muted = false, enabled = true, isActivelyPlaying = true))

        assertTrue(tracker.onDeviceVolumeChanged(volume = 5, muted = true, enabled = true, isActivelyPlaying = true))
    }

    @Test
    fun `does not pause when feature is disabled and keeps updating baseline`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 5, muted = false, enabled = false, isActivelyPlaying = true))
        assertFalse(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = false, isActivelyPlaying = true))

        // 开启开关后若收到重复 0 回调，因基线已是 0，不应误暂停
        assertFalse(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = true))

        // 调高后再降至 0 应正常暂停
        assertFalse(tracker.onDeviceVolumeChanged(volume = 3, muted = false, enabled = true, isActivelyPlaying = true))
        assertTrue(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = true))
    }

    @Test
    fun `does not pause when player is not actively playing`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 5, muted = false, enabled = true, isActivelyPlaying = false))

        assertFalse(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = false))
    }

    @Test
    fun `raising volume from zero does not trigger pause`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = true))

        assertFalse(tracker.onDeviceVolumeChanged(volume = 1, muted = false, enabled = true, isActivelyPlaying = true))
    }

    @Test
    fun `repeated zero callbacks do not re-trigger pause`() {
        val tracker = DeviceVolumeAutoPauseTracker()
        assertFalse(tracker.onDeviceVolumeChanged(volume = 4, muted = false, enabled = true, isActivelyPlaying = true))
        assertTrue(tracker.onDeviceVolumeChanged(volume = 0, muted = false, enabled = true, isActivelyPlaying = true))

        assertFalse(tracker.onDeviceVolumeChanged(volume = 0, muted = true, enabled = true, isActivelyPlaying = true))
    }
}
