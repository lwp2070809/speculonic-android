package de.lwp2070809.speculonic.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Marker for the authenticated original-file endpoint on the configured server. */
internal object OriginalDownloadRequest

/** One Media3 download attempt. Only completed, validated content is handed to the exporter. */
@UnstableApi
internal class RawAudioDownloader(
    private val request: DownloadRequest,
    private val persistent: Cache,
    private val playback: Cache,
    private val client: OkHttpClient,
    private val currentMd5: () -> String?
) : Downloader {
    private val id = request.id
    private val playKey = PlaybackBuffer.rawKey(id)
    private val buffer = ByteArray(64 * 1024)
    @Volatile private var cancelled = false
    @Volatile private var call: Call? = null
    private var integrityFailure: IOException? = null

    companion object {
        fun handles(request: DownloadRequest): Boolean = request.uri.lastPathSegment?.removeSuffix(".view") == "download" &&
            request.uri.getQueryParameter("format") == "raw" && request.uri.getQueryParameter("id") == request.id &&
            (request.customCacheKey == null || request.customCacheKey == request.id)

        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        private val MD5 = Regex("[0-9a-fA-F]{32}")
    }

    override fun download(progressListener: Downloader.ProgressListener?) {
        integrityFailure?.let { throw it }
        ensureRunning()
        val digest = currentMd5()?.takeIf(MD5::matches)
        val cached = persistent.getCachedSpans(id).isNotEmpty() || playback.getCachedSpans(playKey).isNotEmpty()
        val transfer = if (cached) {
            try {
                reuseOrFetch(digest, progressListener)
            } catch (_: RepresentationChanged) {
                Transfer(fetchWhole(progressListener))
            }
        } else {
            Transfer(fetchWhole(progressListener))
        }
        if (digest == null || matchesDigest(transfer.length, digest)) return

        if (transfer.reused) {
            val length = fetchWhole(progressListener)
            if (matchesDigest(length, currentMd5()?.takeIf(MD5::matches) ?: digest)) return
        }
        persistent.removeResource(id)
        throw IOException("Original audio checksum mismatch after fresh download").also { integrityFailure = it }
    }

    private data class Transfer(val length: Long, val reused: Boolean = false)

    private fun reuseOrFetch(digest: String?, progressListener: Downloader.ProgressListener?): Transfer {
        val authorized = exchange("bytes=0-0") { probe ->
            if (probe.code == 200) return Transfer(replaceWith(probe, progressListener))
            val length = responseLength(probe, 0, 0)
            if (probe.body.byteStream().read() < 0) throw EOFException("Empty authorization response")
            AuthorizedRange(length, probe.header("ETag"), PlaybackBuffer.proof(probe))
        }

        val proof = authorized.proof
        if (digest == null && proof == null) {
            return Transfer(fetchWhole(progressListener))
        }
        if (digest == null && persistent.getContentMetadata(id).get(PlaybackBuffer.PROOF, null as String?) != proof) {
            persistent.removeResource(id)
        }
        // Digest-based assembly can include unproven playback bytes until verification finishes.
        // A process restart must never treat that intermediate file as an ETag-verified result.
        recordPersistentProof(if (digest == null) proof else null)
        return assemble(authorized, digest != null, progressListener)
    }

    private data class AuthorizedRange(val length: Long, val tag: String?, val proof: String?)

    private fun assemble(authorized: AuthorizedRange, hasDigest: Boolean, listener: Downloader.ProgressListener?): Transfer {
        val (length, tag, proof) = authorized
        // Old content length could truncate a replacement; use today's authorized representation.
        setLength(length)
        var position = 0L
        var reused = false
        while (position < length) {
            ensureRunning()
            val cached = persistent.getCachedLength(id, position, length - position)
            if (cached > 0) {
                reused = true
                position += cached
                continue
            }
            val hole = -cached
            var gap = hole
            val local = synchronized(playback) {
                val eligible = hasDigest || (proof != null && playback.getContentMetadata(playKey)
                    .get(PlaybackBuffer.PROOF, null as String?) == proof)
                if (!eligible) return@synchronized null
                val span = playback.startReadWriteNonBlocking(playKey, position, hole) ?: return@synchronized null
                if (span.isHoleSpan) {
                    gap = if (span.isOpenEnded) hole else minOf(hole, span.length)
                    playback.releaseHoleSpan(span)
                    return@synchronized null
                }
                val offset = position - span.position
                val file = try { FileInputStream(requireNotNull(span.file)) } catch (_: java.io.FileNotFoundException) {
                    return@synchronized null
                }
                file.channel.position(offset)
                file to minOf(hole, span.length - offset)
            }
            if (local != null) {
                reused = true
                local.first.use { write(it, position, local.second, length, listener) }
                position += local.second
            } else {
                val end = position + gap - 1
                val replacementLength = exchange("bytes=$position-$end", if (hasDigest) null else tag) { response ->
                    if (response.code == 200) {
                        replaceWith(response, listener)
                    } else {
                        if (responseLength(response, position, end) != length ||
                            (!hasDigest && PlaybackBuffer.proof(response) != proof)
                        ) throw RepresentationChanged()
                        write(response.body.byteStream(), position, gap, length, listener)
                        null
                    }
                }
                replacementLength?.let { return Transfer(it) }
                position += gap
            }
        }
        if (!persistent.isCached(id, 0, length)) throw IOException("Audio evicted before completion")
        listener?.onProgress(length, length, 100f)
        return Transfer(length, reused)
    }

    private fun fetchWhole(listener: Downloader.ProgressListener?): Long =
        exchange(null) { response ->
            if (response.code != 200) throw IOException("Expected complete audio response")
            replaceWith(response, listener)
        }

    private fun replaceWith(response: Response, listener: Downloader.ProgressListener?): Long {
        persistent.removeResource(id)
        recordPersistentProof(PlaybackBuffer.proof(response))
        val declared = response.body.contentLength()
        setLength(declared)
        val count = write(response.body.byteStream(), 0, declared, declared, listener)
        if (count == 0L || !persistent.isCached(id, 0, count)) throw IOException("Incomplete original audio")
        setLength(count)
        return count
    }

    private fun setLength(length: Long) {
        val changes = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(changes, length)
        persistent.applyContentMetadataMutations(id, changes)
    }

    private fun recordPersistentProof(proof: String?) {
        val change = ContentMetadataMutations()
        if (proof == null) change.remove(PlaybackBuffer.PROOF) else change.set(PlaybackBuffer.PROOF, proof)
        persistent.applyContentMetadataMutations(id, change)
    }

    private fun write(input: InputStream, start: Long, count: Long, total: Long, listener: Downloader.ProgressListener?): Long {
        if (count == 0L) throw EOFException("Empty audio")
        val lock = persistent.startReadWrite(id, start, count)
        if (!lock.isHoleSpan) throw IOException("Download cache changed during transfer")
        var copied = 0L
        try {
            val sink = CacheDataSink(persistent, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            val spec = DataSpec.Builder().setUri(request.uri).setKey(id).setPosition(start).setLength(count)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
            try {
                sink.open(spec)
                while (count == C.LENGTH_UNSET.toLong() || copied < count) {
                    ensureRunning()
                    val wanted = if (count < 0) buffer.size else minOf(buffer.size.toLong(), count - copied).toInt()
                    val n = input.read(buffer, 0, wanted)
                    ensureRunning()
                    if (n < 0) {
                        if (count >= 0) throw EOFException("Short audio response")
                        break
                    }
                    sink.write(buffer, 0, n)
                    copied += n
                    val done = start + copied
                    listener?.onProgress(total, done, if (total > 0) 100f * done / total else C.PERCENTAGE_UNSET.toFloat())
                }
            } finally {
                sink.close()
            }
        } finally {
            persistent.releaseHoleSpan(lock)
        }
        return copied
    }

    private fun matchesDigest(length: Long, expected: String): Boolean {
        val digest = MessageDigest.getInstance("MD5")
        val spec = DataSpec.Builder().setUri(Uri.EMPTY).setKey(id).setLength(length).build()
        DataSourceInputStream(CacheDataSource(persistent, null), spec).use { input ->
            while (true) {
                ensureRunning()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
    }

    private fun responseLength(response: Response, start: Long, end: Long): Long {
        val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())?.groupValues
        val total = range?.get(3)?.toLongOrNull()
        if (response.code == 206 && total != null && total <= end) throw RepresentationChanged()
        if (response.code != 206 || total == null ||
            range[1].toLongOrNull() != start || range[2].toLongOrNull() != end
        ) throw IOException("Invalid audio Content-Range")
        return total
    }

    private class RepresentationChanged : IOException()

    private inline fun <T> exchange(range: String?, ifMatch: String? = null, consume: (Response) -> T): T {
        val builder = Request.Builder().tag(OriginalDownloadRequest::class.java, OriginalDownloadRequest).url(request.uri.toString()).header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
        if (range != null) builder.header("Range", range)
        if (ifMatch != null) builder.header("If-Match", ifMatch)
        val transfer = client.newCall(builder.build())
        call = transfer
        try {
            ensureRunning()
            return transfer.execute().use { response ->
                if ((ifMatch != null && response.code == 412) || (range != null && response.code == 416)) {
                    throw RepresentationChanged()
                }
                val mime = response.body.contentType()?.toString().orEmpty().lowercase()
                if (!response.isSuccessful || mime.contains("json") || mime.contains("xml") || mime.startsWith("text/"))
                    throw IOException("Download endpoint rejected audio (HTTP ${response.code})")
                consume(response)
            }
        } finally {
            call = null
        }
    }

    private fun ensureRunning() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    override fun cancel() {
        cancelled = true
        call?.cancel()
    }

    override fun remove() = persistent.removeResource(id)
}
