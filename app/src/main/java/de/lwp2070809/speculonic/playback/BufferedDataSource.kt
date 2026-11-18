package de.lwp2070809.speculonic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

@OptIn(UnstableApi::class)
class BufferedDataSource(
    private val upstream: DataSource,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE_BYTES
) : DataSource {

    // 按需在 open 时分配、close 时置空释放，避免双源兜底壳在未触发分支闲置占用 1MB 堆内存
    private var buffer: ByteArray? = null
    private var bufferPos = 0
    private var bufferLimit = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        bufferPos = 0
        bufferLimit = 0
        val length = upstream.open(dataSpec)
        if (buffer == null) {
            buffer = ByteArray(bufferSize)
        }
        return length
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (bufferPos >= bufferLimit) {
            if (length >= bufferSize) {
                return upstream.read(target, offset, length)
            }

            val activeBuffer = buffer ?: ByteArray(bufferSize).also { buffer = it }
            val bytesRead = upstream.read(activeBuffer, 0, bufferSize)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return C.RESULT_END_OF_INPUT
            }
            bufferPos = 0
            bufferLimit = bytesRead
        }

        val activeBuffer = buffer ?: return C.RESULT_END_OF_INPUT
        val bytesAvailable = bufferLimit - bufferPos
        val bytesToCopy = min(length, bytesAvailable)
        System.arraycopy(activeBuffer, bufferPos, target, offset, bytesToCopy)
        bufferPos += bytesToCopy

        return bytesToCopy
    }

    override fun getUri(): Uri? {
        return upstream.uri
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            bufferPos = 0
            bufferLimit = 0
            buffer = null
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 512 * 1024
    }
}
