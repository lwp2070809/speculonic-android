package de.lwp2070809.speculonic.data

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadataMutations
import de.lwp2070809.speculonic.util.MediaFormatUtils
import okhttp3.HttpUrl
import okhttp3.Response
import okio.ByteString.Companion.encodeUtf8

// This header is produced locally after redirects. Never accept it from the server.
internal object RawResourceIdentity {
    const val HEADER = "X-Speculonic-Raw-Resource"

    fun attach(response: Response, authenticatedUrl: HttpUrl?): Response {
        if (authenticatedUrl?.queryParameter("format") != "raw" && response.header(HEADER) == null) return response
        val builder = response.newBuilder().removeHeader(HEADER)
        if (authenticatedUrl?.queryParameter("format") == "raw") {
            val scope = authenticatedUrl.newBuilder().removeAllQueryParameters("s").removeAllQueryParameters("t")
            val identity = if (response.priorResponse == null) {
                scope.build().toString()
            } else {
                // Stream and download may redirect to one file. Retain the account, song,
                // representation parameters and exact final resource, including its query.
                scope.removePathSegment(authenticatedUrl.pathSegments.lastIndex).build().toString() +
                    "\n" + response.request.url
            }
            builder.header(HEADER, identity.encodeUtf8().sha256().hex())
        }
        return builder.build()
    }
}

@UnstableApi
internal object PlaybackBuffer : CacheKeyFactory {
    const val PROOF = "speculonic.rawRepresentation"
    private const val MIXED = "unverified"

    fun rawKey(songId: String) = key(songId, "raw")
    private fun key(songId: String, format: String) = "playback-v1:$format:$songId"

    fun proof(response: Response) = proof(response.request.url.toString(), response.header("ETag"),
        response.header(RawResourceIdentity.HEADER))

    private fun proof(finalUrl: String?, tag: String?, resource: String?): String? {
        if (finalUrl == null || tag == null || !STRONG_ETAG.matches(tag)) return null
        return (resource ?: finalUrl.encodeUtf8().sha256().hex()) + ":" + tag
    }

    private val STRONG_ETAG = Regex("\"[!#-~\\x80-\\xff]*\"")

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val id = dataSpec.key ?: return dataSpec.uri.toString()
        val format = dataSpec.uri.getQueryParameter("format")
        return if (format != null && dataSpec.uri.getQueryParameter("id") == id) key(id, format) else id
    }

    fun remove(cache: Cache, songId: String) {
        cache.removeResource(songId)
        cache.removeResource(rawKey(songId))
        for (format in MediaFormatUtils.SUPPORTED_TRANSCODE_FORMATS) cache.removeResource(key(songId, format))
    }

    fun playerFactory(cache: Cache, upstreamFactory: DataSource.Factory) = DataSource.Factory {
        val upstream = upstreamFactory.createDataSource()
        val sink = CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
        val recording = object : DataSink by sink {
            override fun open(dataSpec: DataSpec) {
                val key = requireNotNull(dataSpec.key)
                if (key.startsWith("playback-v1:raw:")) {
                    val headers = upstream.responseHeaders
                    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()
                    val responseProof = proof(upstream.uri?.toString(), header("ETag"), header(RawResourceIdentity.HEADER))
                    synchronized(cache) {
                        val previous = cache.getContentMetadata(key).get(PROOF, null as String?)
                        // Invalidate BEFORE any byte from a different response can be committed.
                        val next = when {
                            responseProof == null -> MIXED
                            previous == responseProof -> responseProof
                            previous == null && cache.getCachedSpans(key).isEmpty() -> responseProof
                            else -> MIXED
                        }
                        if (previous != next) cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(PROOF, next))
                    }
                }
                sink.open(dataSpec)
            }
        }
        CacheDataSource(cache, upstream, FileDataSource(), recording, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR, null, this)
    }
}
