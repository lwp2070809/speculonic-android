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
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.net.HttpURLConnection.HTTP_PRECON_FAILED
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Marker for the authenticated original-file endpoint on the configured server. */
internal object OriginalDownloadRequest {
    const val RANGE_NOT_SATISFIABLE = 416
}

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

        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
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
            } catch (_: FullDownloadRequired) {
                Transfer(fetchWhole(progressListener))
            }
        } else {
            Transfer(fetchWhole(progressListener))
        }
        if (digest == null) return
        val actual = cachedMd5(transfer.length)
        if (actual.equals(digest, ignoreCase = true)) return

        val fresh = if (transfer.reused) cachedMd5(fetchWhole(progressListener)) else actual
        val expected = currentMd5()?.takeIf(MD5::matches) ?: digest
        if (fresh.equals(expected, ignoreCase = true)) return
        persistent.removeResource(id)
        throw IOException("Original audio checksum mismatch after fresh download").also { integrityFailure = it }
    }

    private data class Transfer(val length: Long, val reused: Boolean = false)

    private fun reuseOrFetch(digest: String?, progressListener: Downloader.ProgressListener?): Transfer {
        val authorized = exchange("bytes=0-0") { probe ->
            if (probe.code == HTTP_OK) return Transfer(replaceWith(probe, progressListener))
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
            val (local, gap) = playbackSegment(position, hole, hasDigest, proof)
            if (local != null) {
                reused = true
                local.use { write(it, position, gap, length, listener) }
                position += gap
            } else {
                val end = position + gap - 1
                val replacementLength = exchange("bytes=$position-$end", if (hasDigest) null else tag) { response ->
                    if (response.code == HTTP_OK) {
                        replaceWith(response, listener)
                    } else {
                        responseLength(response, position, end, expectedTotal = length)
                        if (!hasDigest && PlaybackBuffer.proof(response) != proof) throw FullDownloadRequired()
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

    private fun playbackSegment(position: Long, hole: Long, hasDigest: Boolean, proof: String?): Pair<FileInputStream?, Long> =
        synchronized(playback) {
            val hasProof = proof != null && playback.getContentMetadata(playKey).get(PlaybackBuffer.PROOF, null as String?) == proof
            if (!hasDigest && !hasProof) return@synchronized null to hole
            val span = playback.startReadWriteNonBlocking(playKey, position, hole) ?: return@synchronized null to hole
            if (span.isHoleSpan) {
                playback.releaseHoleSpan(span)
                return@synchronized null to if (span.isOpenEnded) hole else minOf(hole, span.length)
            }
            val offset = position - span.position
            val file = try {
                FileInputStream(requireNotNull(span.file))
            } catch (_: java.io.FileNotFoundException) {
                return@synchronized null to hole
            }
            try {
                file.channel.position(offset)
            } catch (e: IOException) {
                file.close()
                throw e
            }
            file to minOf(hole, span.length - offset)
        }

    private fun fetchWhole(listener: Downloader.ProgressListener?): Long =
        exchange(null) { response ->
            if (response.code != HTTP_OK) throw IOException("Expected complete audio response")
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
        try {
            val sink = CacheDataSink(persistent, CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            val spec = DataSpec.Builder().setUri(request.uri).setKey(id).setPosition(start).setLength(count)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
            try {
                sink.open(spec)
                return copyToSink(input, sink, spec, total, listener)
            } finally {
                sink.close()
            }
        } finally {
            persistent.releaseHoleSpan(lock)
        }
    }

    private fun copyToSink(
        input: InputStream, sink: CacheDataSink, spec: DataSpec, total: Long, listener: Downloader.ProgressListener?
    ): Long {
        var copied = 0L
        val count = spec.length
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
            val done = spec.position + copied
            listener?.onProgress(total, done, if (total > 0) 100f * done / total else C.PERCENTAGE_UNSET.toFloat())
        }
        return copied
    }

    private fun cachedMd5(length: Long): String {
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun responseLength(response: Response, start: Long, end: Long, expectedTotal: Long? = null): Long {
        val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())?.groupValues ?: invalidRange()
        if (response.code != HTTP_PARTIAL || range[1].toLongOrNull() != start) invalidRange()
        val last = range[2].toLongOrNull() ?: invalidRange()
        if (last !in start..end) invalidRange()
        if (range[3] == "*" && last == end) throw FullDownloadRequired()
        return validateRangeTotal(range[3], last, end, expectedTotal)
    }

    private fun validateRangeTotal(value: String, last: Long, end: Long, expected: Long?): Long {
        val total = value.toLongOrNull() ?: invalidRange()
        if (last >= total || (last < end && total != expected)) invalidRange()
        if (last < end || (expected != null && total != expected)) throw FullDownloadRequired()
        return total
    }

    private fun invalidRange(): Nothing = throw IOException("Invalid audio Content-Range")

    private class FullDownloadRequired : IOException()

    private inline fun <T> exchange(range: String?, ifMatch: String? = null, consume: (Response) -> T): T {
        val builder = Request.Builder().tag(OriginalDownloadRequest::class.java, OriginalDownloadRequest).url(request.uri.toString())
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
        if (range != null) builder.header("Range", range)
        if (ifMatch != null) builder.header("If-Match", ifMatch)
        val transfer = client.newCall(builder.build())
        call = transfer
        try {
            ensureRunning()
            return transfer.execute().use { response ->
                val restart = when (response.code) {
                    HTTP_PRECON_FAILED -> ifMatch != null
                    OriginalDownloadRequest.RANGE_NOT_SATISFIABLE -> range != null
                    else -> false
                }
                if (restart) throw FullDownloadRequired()
                val mime = response.body.contentType()?.toString().orEmpty().lowercase()
                val errorBody = mime.contains("json") || mime.contains("xml") || mime.startsWith("text/")
                if (!response.isSuccessful || errorBody)
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
