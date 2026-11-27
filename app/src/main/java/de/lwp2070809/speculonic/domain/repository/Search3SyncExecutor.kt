package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

private class EntityPaginationCursor {
    var hasMore = true
    var offset = 0

    fun advance(label: String, received: Int, added: Int) {
        if (!hasMore) return
        if (received != added) {
            throw IncompletePaginationException("Overlapping search3 $label page at offset $offset")
        }
        // A server may cap the page below the requested size. Probe until an empty page.
        hasMore = received > 0
        offset += received
    }
}

private class Search3PaginationState {
    val artists = EntityPaginationCursor()
    val albums = EntityPaginationCursor()
    val songs = EntityPaginationCursor()
    var page = 0

    fun hasPendingPages(maxPages: Int): Boolean {
        val anyMore = artists.hasMore || albums.hasMore || songs.hasMore
        return anyMore && page < maxPages
    }
}

private class Search3ChannelBuffers {
    val artists = ArrayList<ArtistEntity>()
    val albums = ArrayList<Album>()
    val songs = ArrayList<Song>()
    val tempIds = ArrayList<SyncTempIdEntity>()

    fun isEmpty(): Boolean =
        artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && tempIds.isEmpty()

    fun totalSize(): Int =
        artists.size + albums.size + songs.size + tempIds.size

    fun clear() {
        artists.clear()
        albums.clear()
        songs.clear()
        tempIds.clear()
    }
}

internal class Search3SyncExecutor(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val entityMapper: EntityMapper,
    private val albumListHelper: AlbumListSyncHelper,
    private val json: Json,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit
) {
    suspend fun execute(authParams: Triple<String, String, String>): Unit = coroutineScope {
        val streamState = Search3StreamState()
        val channels = Search3Channels()
        val writerJob = launchWriterJob(channels)
        val pagination = Search3PaginationState()

        try {
            while (pagination.hasPendingPages(MAX_SEARCH3_PAGES)) {
                pagination.page++
                fetchAndDecodePage(authParams, channels, streamState, pagination)
            }
            if (pagination.artists.hasMore || pagination.albums.hasMore || pagination.songs.hasMore) {
                throw IncompletePaginationException("Exceeded search3 page limit ($MAX_SEARCH3_PAGES)")
            }
        } finally {
            channels.closeAll()
            writerJob.join()
        }
    }

    private suspend fun fetchAndDecodePage(
        authParams: Triple<String, String, String>,
        channels: Search3Channels,
        streamState: Search3StreamState,
        pagination: Search3PaginationState
    ) {
        val (u, t, s) = authParams
        val reqArtistCount = if (pagination.artists.hasMore) ARTIST_PAGE_SIZE else 0
        val reqAlbumCount = if (pagination.albums.hasMore) ALBUM_PAGE_SIZE else 0
        val reqSongCount = if (pagination.songs.hasMore) SONG_PAGE_SIZE else 0

        var decodedStats: Search3PageStats? = null
        val responseBody = api.search3Stream(
            query = "",
            u = u,
            t = t,
            s = s,
            artistCount = reqArtistCount,
            artistOffset = pagination.artists.offset,
            albumCount = reqAlbumCount,
            albumOffset = pagination.albums.offset,
            songCount = reqSongCount,
            songOffset = pagination.songs.offset
        )

        responseBody.use { body ->
            ensureJsonResponse(body)
            withContext(Dispatchers.IO) {
                body.byteStream().use { stream ->
                    val streamingSerializer = SearchResult3StreamingSerializer(
                        channels = channels,
                        state = streamState,
                        onPageStats = { decodedStats = it },
                        batchSize = BATCH_SIZE
                    )
                    SubsonicResponseStreamingSerializer(streamingSerializer).decodePage(json, stream)
                }
            }
        }

        val pageStats = checkNotNull(decodedStats) { "Missing searchResult3 in response" }
        pagination.artists.advance("artist", pageStats.artistReceived, pageStats.artistAdded)
        pagination.albums.advance("album", pageStats.albumReceived, pageStats.albumAdded)
        pagination.songs.advance("song", pageStats.songReceived, pageStats.songAdded)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWriterJob(channels: Search3Channels): Job {
        return launch(Dispatchers.IO) {
            try {
                runWriterLoop(channels)
            } catch (t: Throwable) {
                channels.closeAll(t)
                throw t
            }
        }
    }

    private class ChannelStates(
        var artist: Boolean = true,
        var album: Boolean = true,
        var song: Boolean = true,
        var tempId: Boolean = true
    ) {
        fun anyOpen(): Boolean = artist || album || song || tempId
    }

    private suspend fun runWriterLoop(channels: Search3Channels) {
        val buffers = Search3ChannelBuffers()
        val open = ChannelStates()

        while (open.anyOpen()) {
            selectNextBatch(channels, open, buffers)
            if (buffers.totalSize() >= TRANSACTION_FLUSH_THRESHOLD) {
                flushBufferedWrites(buffers)
            }
        }
        flushBufferedWrites(buffers)
    }

    private suspend fun selectNextBatch(
        channels: Search3Channels,
        open: ChannelStates,
        buffers: Search3ChannelBuffers
    ) {
        select<Unit> {
            if (open.artist) {
                channels.artist.onReceiveCatching { res ->
                    res.getOrNull()?.let { buffers.artists.addAll(it) }
                    if (res.isClosed) open.artist = false
                }
            }
            if (open.album) {
                channels.album.onReceiveCatching { res ->
                    res.getOrNull()?.let { buffers.albums.addAll(it) }
                    if (res.isClosed) open.album = false
                }
            }
            if (open.song) {
                channels.song.onReceiveCatching { res ->
                    res.getOrNull()?.let { buffers.songs.addAll(it) }
                    if (res.isClosed) open.song = false
                }
            }
            if (open.tempId) {
                channels.tempId.onReceiveCatching { res ->
                    res.getOrNull()?.let { buffers.tempIds.addAll(it) }
                    if (res.isClosed) open.tempId = false
                }
            }
        }
    }

    private suspend fun flushBufferedWrites(buffers: Search3ChannelBuffers) {
        if (buffers.isEmpty()) return
        // 分批事务提交可显著抑制全量同步期间 InvalidationTracker 的高频 UI 失效刷新
        runInTransaction {
            buffers.artists.chunked(BATCH_SIZE).forEach { musicDao.insertArtists(it) }
            for (batch in buffers.albums.chunked(BATCH_SIZE)) {
                musicDao.insertAlbums(albumListHelper.mergeAlbums(batch))
            }
            for (batch in buffers.songs.chunked(BATCH_SIZE)) {
                val localById = musicDao.getSongsMetadataByIds(batch.map { it.id }).associateBy { it.id }
                musicDao.insertSongs(batch.map { song -> entityMapper.mergeSyncSong(song, localById[song.id]) })
            }
            buffers.tempIds.chunked(BATCH_SIZE).forEach { musicDao.insertSyncTempIds(it) }
        }
        buffers.clear()
    }

    private fun ensureJsonResponse(body: ResponseBody) {
        val contentType = body.contentType()
        val type = contentType?.type ?: ""
        val subtype = contentType?.subtype ?: ""
        val isHtmlType = type.equals("text", ignoreCase = true) || subtype.contains("html", ignoreCase = true)
        check(!isHtmlType) {
            "API returned HTML/Text instead of JSON. Server might be under maintenance or misconfigured."
        }
        val peekLength = minOf(HTML_PEEK_BYTES, body.contentLength().takeIf { it > 0 } ?: HTML_PEEK_BYTES)
        val peekedPrefix = runCatching { body.source().peek().readUtf8(peekLength) }.getOrDefault("")
        val trimmed = peekedPrefix.trimStart()
        val isHtmlPayload = trimmed.startsWith("<") && !trimmed.startsWith("<?xml", ignoreCase = true)
        check(!isHtmlPayload) { "API returned HTML page instead of JSON response." }
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val TRANSACTION_FLUSH_THRESHOLD = 4000
        const val ARTIST_PAGE_SIZE = 1000
        const val ALBUM_PAGE_SIZE = 2000
        const val SONG_PAGE_SIZE = 5000
        const val MAX_SEARCH3_PAGES = 200
        const val HTML_PEEK_BYTES = 128L
    }
}
