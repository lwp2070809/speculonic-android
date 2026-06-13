package de.lwp2070809.speculonic.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
class NetworkRestrictedDataSource(
    private val upstream: DataSource,
    private val checkRestriction: () -> Boolean
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (checkRestriction()) {
            throw NetworkRestrictedException("Network restricted: Metered network usage not allowed for non-cached content")
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (checkRestriction()) {
            throw NetworkRestrictedException("Network restricted: Metered network usage not allowed for non-cached content during playback")
        }
        return upstream.read(buffer, offset, length)
    }
    
    override fun getUri(): Uri? = upstream.uri
    override fun close() = upstream.close()
}
