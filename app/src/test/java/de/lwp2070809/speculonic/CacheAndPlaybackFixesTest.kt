package de.lwp2070809.speculonic

import de.lwp2070809.speculonic.data.CacheExporter
import de.lwp2070809.speculonic.playback.DeviceVolumeAutoPauseTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

class CacheAndPlaybackFixesTest {

    private class ChunkedByteInputStream(
        private val bytes: ByteArray,
        private val maxChunkSize: Int
    ) : InputStream() {
        private var cursor = 0

        override fun read(): Int {
            return if (cursor < bytes.size) bytes[cursor++].toInt() and BYTE_MASK else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cursor >= bytes.size) return -1
            val toRead = min(len, min(maxChunkSize, bytes.size - cursor))
            System.arraycopy(bytes, cursor, b, off, toRead)
            cursor += toRead
            return toRead
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }

    @Test
    fun d05StreamComparisonDetectsDifferentBytesOfEqualSizeAcrossChunkBoundaries() {
        val sampleSize = 200_044
        val sourceBytes = ByteArray(sampleSize) { index -> (index % 251).toByte() }
        val sameBytes = sourceBytes.copyOf()
        val modifiedLastByte = sourceBytes.copyOf().apply {
            this[sampleSize - 1] = (this[sampleSize - 1] + 1).toByte()
        }

        val matchingChunked = CacheExporter.streamsHaveIdenticalContent(
            ChunkedByteInputStream(sourceBytes, maxChunkSize = 777),
            ChunkedByteInputStream(sameBytes, maxChunkSize = 13_107)
        )
        assertTrue(matchingChunked)

        val mismatchedEqualLength = CacheExporter.streamsHaveIdenticalContent(
            ByteArrayInputStream(sourceBytes),
            ByteArrayInputStream(modifiedLastByte)
        )
        assertFalse(mismatchedEqualLength)

        val emptyStreams = CacheExporter.streamsHaveIdenticalContent(
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayInputStream(ByteArray(0))
        )
        assertTrue(emptyStreams)
    }

    @Test
    fun p04ColdStartVolumeDropFromOneToZeroPausesOnFirstCallback() {
        val trackerFromNonZero = DeviceVolumeAutoPauseTracker(initialIsZeroOrMuted = false)

        assertTrue(
            trackerFromNonZero.onDeviceVolumeChanged(
                volume = 0,
                muted = false,
                enabled = true,
                isActivelyPlaying = true
            )
        )
        assertFalse(
            trackerFromNonZero.onDeviceVolumeChanged(
                volume = 0,
                muted = false,
                enabled = true,
                isActivelyPlaying = true
            )
        )
    }

    @Test
    fun p04ColdStartAlreadyZeroDoesNotPauseUntilVolumeRisesThenDrops() {
        val trackerStartingZero = DeviceVolumeAutoPauseTracker(initialIsZeroOrMuted = true)

        assertFalse(
            trackerStartingZero.onDeviceVolumeChanged(
                volume = 0,
                muted = true,
                enabled = true,
                isActivelyPlaying = true
            )
        )
        assertFalse(
            trackerStartingZero.onDeviceVolumeChanged(
                volume = 1,
                muted = false,
                enabled = true,
                isActivelyPlaying = true
            )
        )
        assertTrue(
            trackerStartingZero.onDeviceVolumeChanged(
                volume = 0,
                muted = false,
                enabled = true,
                isActivelyPlaying = true
            )
        )
    }
}
