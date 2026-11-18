package de.lwp2070809.speculonic.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

@UnstableApi
class NetworkRestrictedDataSource(
    private val upstream: DataSource,
    private val checkRestriction: () -> Boolean
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        if (checkRestriction()) {
            throw NetworkRestrictedException("Network restricted: Metered network usage not allowed for non-cached content")
        }
        return upstream.open(dataSpec)
    }

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
}
