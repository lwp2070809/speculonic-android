package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SongMetadata
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.Error
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.SubsonicApiException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class Search3PageStats(
    val artistReceived: Int = 0,
    val artistAdded: Int = 0,
    val albumReceived: Int = 0,
    val albumAdded: Int = 0,
    val songReceived: Int = 0,
    val songAdded: Int = 0
)

@OptIn(ExperimentalSerializationApi::class)
class StreamingListSerializer<T>(
    private val elementSerializer: KSerializer<T>,
    private val onElement: (T) -> Unit
) : KSerializer<List<T>> {
    override val descriptor: SerialDescriptor = ListSerializer(elementSerializer).descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = Unit
    override fun deserialize(decoder: Decoder): List<T> {
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                val element = decodeSerializableElement(descriptor, index, elementSerializer)
                onElement(element)
            }
        }
        return emptyList()
    }
}

@OptIn(ExperimentalSerializationApi::class)
class SearchResult3StreamingSerializer(
    private val artistChannel: Channel<List<ArtistEntity>>,
    private val albumChannel: Channel<List<de.lwp2070809.speculonic.data.db.entities.AlbumEntity>>,
    private val songChannel: Channel<List<de.lwp2070809.speculonic.data.db.entities.SongEntity>>,
    private val tempIdChannel: Channel<List<SyncTempIdEntity>>,
    private val existingAlbumsMap: Map<String, de.lwp2070809.speculonic.data.db.entities.AlbumEntity>,
    private val existingSongsMetadata: Map<String, SongMetadata>,
    private val entityMapper: EntityMapper,
    private val seenArtistIds: MutableSet<String> = mutableSetOf(),
    private val seenAlbumIds: MutableSet<String> = mutableSetOf(),
    private val seenSongIds: MutableSet<String> = mutableSetOf(),
    private val onPageStats: (Search3PageStats) -> Unit = {},
    private val batchSize: Int = 500
) : KSerializer<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SearchResult3") {
        element("artist", ListSerializer(Artist.serializer()).nullable.descriptor)
        element("album", ListSerializer(Album.serializer()).nullable.descriptor)
        element("song", ListSerializer(Song.serializer()).nullable.descriptor)
    }

    override fun serialize(encoder: Encoder, value: Unit) = Unit

    override fun deserialize(decoder: Decoder) {
        var artistReceived = 0
        var artistAdded = 0
        var albumReceived = 0
        var albumAdded = 0
        var songReceived = 0
        var songAdded = 0

        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                when (index) {
                    0 -> decodeArray(this, 0, Artist.serializer()) { artists ->
                        artistReceived += artists.size
                        val newArtists = artists.filter { seenArtistIds.add(it.id) }
                        if (newArtists.isNotEmpty()) {
                            artistAdded += newArtists.size
                            val entities = newArtists.map { artist ->
                                val count = artist.albumCount ?: artist.album.size
                                ArtistEntity(artist.id, artist.name, artist.coverArt, count)
                            }
                            artistChannel.sendSafe(entities)
                            tempIdChannel.sendSafe(entities.map { SyncTempIdEntity(it.id, "artist") })
                        }
                    }
                    1 -> decodeArray(this, 1, Album.serializer()) { albums ->
                        albumReceived += albums.size
                        val newAlbums = albums.filter { seenAlbumIds.add(it.id) }
                        if (newAlbums.isNotEmpty()) {
                            albumAdded += newAlbums.size
                            val entities = newAlbums.map {
                                entityMapper.albumToEntity(it, existing = existingAlbumsMap[it.id], isStarred = it.starred != null)
                            }
                            albumChannel.sendSafe(entities)
                            tempIdChannel.sendSafe(entities.map { SyncTempIdEntity(it.id, "album") })
                        }
                    }
                    2 -> decodeArray(this, 2, Song.serializer()) { songs ->
                        songReceived += songs.size
                        val newSongs = songs.filter { seenSongIds.add(it.id) }
                        if (newSongs.isNotEmpty()) {
                            songAdded += newSongs.size
                            val entities = newSongs.map { song ->
                                val meta = existingSongsMetadata[song.id]
                                val isStarred = song.starred != null
                                val lastUpdated = if (isStarred && meta?.starred != true) null else meta?.lastUpdated
                                entityMapper.songToEntity(
                                    song,
                                    albumId = song.albumId ?: song.parent,
                                    localUri = meta?.localUri,
                                    isCached = meta?.isFullyCached ?: false,
                                    isTranscoded = meta?.isTranscoded ?: false,
                                    isStarred = isStarred,
                                    lastUpdated = lastUpdated
                                )
                            }
                            songChannel.sendSafe(entities)
                            tempIdChannel.sendSafe(entities.map { SyncTempIdEntity(it.id, "song") })
                        }
                    }
                    else -> skipUnknownField(index)
                }
            }
        }

        onPageStats(
            Search3PageStats(
                artistReceived = artistReceived,
                artistAdded = artistAdded,
                albumReceived = albumReceived,
                albumAdded = albumAdded,
                songReceived = songReceived,
                songAdded = songAdded
            )
        )
    }

    private fun CompositeDecoder.skipUnknownField(index: Int) {
        if (this is JsonDecoder) {
            decodeJsonElement()
        } else {
            decodeStringElement(descriptor, index)
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun <E> Channel<E>.sendSafe(element: E) {
        if (isClosedForSend) return
        val result = trySend(element)
        if (result.isSuccess || result.isClosed) return

        runBlocking {
            kotlinx.coroutines.withTimeout(SEND_TIMEOUT_MS) {
                send(element)
            }
        }
    }

    private fun <T> decodeArray(decoder: CompositeDecoder, index: Int, serializer: KSerializer<T>, onChunk: (List<T>) -> Unit) {
        val chunk = mutableListOf<T>()
        val listSerializer = StreamingListSerializer(serializer) { element ->
            chunk.add(element)
            if (chunk.size >= batchSize) {
                onChunk(chunk.toList())
                chunk.clear()
            }
        }.nullable
        decoder.decodeNullableSerializableElement(descriptor, index, listSerializer)
        if (chunk.isNotEmpty()) {
            onChunk(chunk.toList())
        }
    }

    companion object {
        private const val SEND_TIMEOUT_MS = 15_000L
    }
}

@OptIn(ExperimentalSerializationApi::class)
class SubsonicResponseStreamingSerializer(
    searchResult3Serializer: KSerializer<Unit>
) : KSerializer<Unit> {
    private val nullableSearchResult3Serializer = searchResult3Serializer.nullable

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SubsonicResponse") {
        element("subsonic-response", buildClassSerialDescriptor("ResponseContent") {
            element("status", String.serializer().nullable.descriptor)
            element("searchResult3", nullableSearchResult3Serializer.descriptor)
            element("error", Error.serializer().nullable.descriptor)
        })
    }

    override fun serialize(encoder: Encoder, value: Unit) = Unit

    override fun deserialize(decoder: Decoder) {
        var hasResponseRoot = false
        var status: String? = null
        var apiErrorCode: Int? = null
        var apiErrorMessage: String? = null
        val contentDescriptor = descriptor.getElementDescriptor(0)
        val contentSerializer = object : KSerializer<Unit> {
            override val descriptor: SerialDescriptor = contentDescriptor
            override fun serialize(encoder: Encoder, value: Unit) = Unit
            override fun deserialize(decoder: Decoder) {
                hasResponseRoot = true
                decoder.decodeStructure(contentDescriptor) {
                    while (true) {
                        val contentIndex = decodeElementIndex(contentDescriptor)
                        if (contentIndex == CompositeDecoder.DECODE_DONE) break
                        when (contentIndex) {
                            IDX_STATUS -> {
                                status = if (this is JsonDecoder) {
                                    (decodeJsonElement() as? JsonPrimitive)?.contentOrNull
                                } else {
                                    decodeNullableSerializableElement(
                                        contentDescriptor,
                                        IDX_STATUS,
                                        String.serializer().nullable
                                    )
                                }
                            }
                            IDX_SEARCH_RESULT_3 -> decodeNullableSerializableElement(
                                contentDescriptor,
                                IDX_SEARCH_RESULT_3,
                                nullableSearchResult3Serializer
                            )
                            IDX_ERROR -> {
                                if (this is JsonDecoder) {
                                    val elem = decodeJsonElement()
                                    if (elem is JsonObject) {
                                        apiErrorCode = (elem["code"] as? JsonPrimitive)?.intOrNull
                                        apiErrorMessage = (elem["message"] as? JsonPrimitive)?.contentOrNull
                                    }
                                } else {
                                    val err = decodeNullableSerializableElement(
                                        contentDescriptor,
                                        IDX_ERROR,
                                        Error.serializer().nullable
                                    )
                                    if (err != null) {
                                        apiErrorCode = err.code
                                        apiErrorMessage = err.message
                                    }
                                }
                            }
                            else -> {
                                if (this is JsonDecoder) {
                                    decodeJsonElement()
                                } else {
                                    decodeStringElement(contentDescriptor, contentIndex)
                                }
                            }
                        }
                    }
                }
            }
        }.nullable

        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index == 0) {
                    decodeNullableSerializableElement(descriptor, 0, contentSerializer)
                } else if (this is JsonDecoder) {
                    decodeJsonElement()
                }
            }
        }

        if (!hasResponseRoot) {
            throw SubsonicApiException(null, "Missing subsonic-response root object")
        }
        if (status != "ok") {
            throw SubsonicApiException(
                apiErrorCode,
                apiErrorMessage ?: "Subsonic streaming response status is ${status ?: "missing"}"
            )
        }
    }

    companion object {
        private const val IDX_STATUS = 0
        private const val IDX_SEARCH_RESULT_3 = 1
        private const val IDX_ERROR = 2
    }
}
