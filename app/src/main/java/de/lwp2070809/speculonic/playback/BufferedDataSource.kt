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
    private val bufferSize: Int = 512 * 1024 
) : DataSource {

    private val buffer = ByteArray(bufferSize)
    private var bufferPos = 0
    private var bufferLimit = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        
        bufferPos = 0
        bufferLimit = 0
        return upstream.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        
        if (bufferPos >= bufferLimit) {
            
            
            if (length >= bufferSize) {
                return upstream.read(target, offset, length)
            }

            
            val bytesRead = upstream.read(buffer, 0, bufferSize)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return C.RESULT_END_OF_INPUT
            }
            bufferPos = 0
            bufferLimit = bytesRead
        }

        
        val bytesAvailable = bufferLimit - bufferPos
        val bytesToCopy = min(length, bytesAvailable)
        System.arraycopy(buffer, bufferPos, target, offset, bytesToCopy)
        bufferPos += bytesToCopy

        return bytesToCopy
    }

    override fun getUri(): Uri? {
        return upstream.uri
    }

    override fun close() {
        bufferPos = 0
        bufferLimit = 0
        upstream.close()
    }
}
