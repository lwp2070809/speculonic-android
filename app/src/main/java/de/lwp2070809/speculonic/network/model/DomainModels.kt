package de.lwp2070809.speculonic.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int? = 0,
    val starred: String? = null,
    val album: List<Album> = emptyList()
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = 0,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val song: List<Song> = emptyList(),
    val discTitles: List<DiscTitle> = emptyList()
)

@Serializable
data class DiscTitle(
    val disc: Int,
    val title: String,
    val coverArt: String? = null
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val isVideo: Boolean? = false,
    val starred: String? = null,
    val discNumber: Int? = null,
    val md5: String? = null,
    @kotlinx.serialization.Transient
    val localUri: String? = null,
    @kotlinx.serialization.Transient
    val isFullyCached: Boolean = false,
    @kotlinx.serialization.Transient
    val isTranscoded: Boolean = false
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = false,
    val songCount: Int? = 0,
    val duration: Int? = 0,
    val created: String? = null,
    val coverArt: String? = null,
    val entry: List<Song> = emptyList(),
    @kotlinx.serialization.Transient
    val pinned: Boolean = false,
    @kotlinx.serialization.Transient
    val lastUpdated: Long = 0L
)

@Serializable
data class Lyrics(
    val artist: String? = null,
    val title: String? = null,
    @SerialName("value")
    val content: String? = null
)

@Serializable
data class StructuredLyrics(
    val lang: String,
    val synced: Boolean,
    val line: List<LyricsLine>,
    val displayTitle: String? = null,
    val offset: Int? = null
)

@Serializable
data class LyricsLine(
    val value: String,
    val start: Long? = null
)

@Serializable
data class OpenSubsonicExtension(
    val name: String,
    val versions: List<Int>
)

@Serializable
data class SearchResult3(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

enum class PlaylistAddResult {
    SUCCESS, ERROR, ALREADY_EXISTS
}

@Serializable
data class AlbumList2Response(
    val album: List<Album> = emptyList()
)

@Serializable
data class PlaylistsResponse(
    val playlist: List<Playlist> = emptyList()
)

@Serializable
data class Starred2Response(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList()
)

@Serializable
data class ArtistsResponse(
    val ignoredArticles: String? = null,
    val index: List<Index> = emptyList()
)

@Serializable
data class Indexes(
    val child: List<Song> = emptyList(),
    val shortcut: List<Artist> = emptyList(),
    val index: List<Index> = emptyList(),
    val lastModified: Long? = 0
)

@Serializable
data class Index(
    val name: String,
    val artist: List<Artist> = emptyList()
)

@Serializable
data class LyricsListResponse(
    val structuredLyrics: List<StructuredLyrics> = emptyList(),
    val lyrics: List<Lyrics> = emptyList()
)
