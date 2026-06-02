package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.SongMetadata
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.network.model.Song
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure

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
    private val onSongCount: (Int) -> Unit,
    private val batchSize: Int = 500
) : KSerializer<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SearchResult3") {
        element("artist", ListSerializer(Artist.serializer()).descriptor)
        element("album", ListSerializer(Album.serializer()).descriptor)
        element("song", ListSerializer(Song.serializer()).descriptor)
    }

    override fun serialize(encoder: Encoder, value: Unit) = Unit

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                when (index) {
                    0 -> decodeArray(this, 0, Artist.serializer()) { artists ->
                        val entities = artists.map { artist ->
                            val count = artist.albumCount ?: artist.album.size
                            ArtistEntity(artist.id, artist.name, artist.coverArt, count) 
                        }
                        
                        runBlocking {
                            artistChannel.send(entities)
                            tempIdChannel.send(entities.map { SyncTempIdEntity(it.id, "artist") })
                        }
                    }
                    1 -> decodeArray(this, 1, Album.serializer()) { albums ->
                        val entities = albums.map { entityMapper.albumToEntity(it, existing = existingAlbumsMap[it.id], isStarred = it.starred != null) }
                        runBlocking {
                            albumChannel.send(entities)
                            tempIdChannel.send(entities.map { SyncTempIdEntity(it.id, "album") })
                        }
                    }
                    2 -> {
                        var total = 0
                        decodeArray(this, 2, Song.serializer()) { songs ->
                            total += songs.size
                            val entities = songs.map { song ->
                                val meta = existingSongsMetadata[song.id]
                                val isStarred = if (song.starred != null) true else (meta?.starred ?: false)
                                entityMapper.songToEntity(
                                    song,
                                    albumId = song.albumId ?: song.parent,
                                    localUri = meta?.localUri,
                                    isCached = meta?.isFullyCached ?: false,
                                    isStarred = isStarred,
                                    lastUpdated = meta?.lastUpdated
                                )
                            }
                            runBlocking {
                                songChannel.send(entities)
                                tempIdChannel.send(entities.map { SyncTempIdEntity(it.id, "song") })
                            }
                        }
                        onSongCount(total)
                    }
                }
            }
        }
    }

    private fun <T> decodeArray(decoder: CompositeDecoder, index: Int, serializer: KSerializer<T>, onChunk: (List<T>) -> Unit) {
        val chunk = mutableListOf<T>()
        decoder.decodeSerializableElement(descriptor, index, StreamingListSerializer(serializer) { element ->
            chunk.add(element)
            if (chunk.size >= batchSize) {
                onChunk(chunk.toList())
                chunk.clear()
            }
        })
        if (chunk.isNotEmpty()) {
            onChunk(chunk.toList())
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
class SubsonicResponseStreamingSerializer(
    private val searchResult3Serializer: KSerializer<Unit>
) : KSerializer<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SubsonicResponse") {
        element("subsonic-response", buildClassSerialDescriptor("ResponseContent") {
            element("status", buildClassSerialDescriptor("String"))
            element("version", buildClassSerialDescriptor("String"))
            element("searchResult3", searchResult3Serializer.descriptor)
        })
    }

    override fun serialize(encoder: Encoder, value: Unit) = Unit

    override fun deserialize(decoder: Decoder) {
        decoder.decodeStructure(descriptor) {
            val index = decodeElementIndex(descriptor)
            if (index == 0) {
                val contentDescriptor = descriptor.getElementDescriptor(0)
                decodeSerializableElement(descriptor, 0, object : KSerializer<Unit> {
                    override val descriptor: SerialDescriptor = contentDescriptor
                    override fun serialize(encoder: Encoder, value: Unit) = Unit
                    override fun deserialize(decoder: Decoder) {
                        decoder.decodeStructure(contentDescriptor) {
                            while (true) {
                                val contentIndex = decodeElementIndex(contentDescriptor)
                                if (contentIndex == CompositeDecoder.DECODE_DONE) break
                                when (contentIndex) {
                                    2 -> decodeSerializableElement(contentDescriptor, 2, searchResult3Serializer)
                                    else -> decodeStringElement(contentDescriptor, contentIndex) 
                                }
                            }
                        }
                    }
                })
            }
        }
    }
}
