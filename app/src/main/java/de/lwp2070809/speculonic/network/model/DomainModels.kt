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
    val album: List<Album> = emptyList(),
    val artistImageUrl: String? = null,
    val musicBrainzId: String? = null,
    val sortName: String? = null,
    val roles: List<String> = emptyList()
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = 0,
    val duration: Int? = 0,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val song: List<Song> = emptyList(),
    val version: String? = null,
    val userRating: Int? = null,
    val musicBrainzId: String? = null,
    val genres: List<ItemGenre> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val displayArtist: String? = null,
    val releaseDate: ItemDate? = null,
    val isCompilation: Boolean? = null
)

@Serializable
data class Song(
    val id: String,
    val parent: String? = null,
    val isDir: Boolean = false,
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
    val userRating: Int? = null,
    val discNumber: Int? = null,
    val musicBrainzId: String? = null,
    val md5: String? = null,
    val genres: List<ItemGenre> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val displayArtist: String? = null,
    val bpm: Int? = null,
    val comment: String? = null,
    val replayGain: ReplayGain? = null,
    @kotlinx.serialization.Transient
    val localUri: String? = null,
    @kotlinx.serialization.Transient
    val isFullyCached: Boolean = false
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
    val readonly: Boolean? = null,
    @kotlinx.serialization.Transient
    val pinned: Boolean = false,
    @kotlinx.serialization.Transient
    val lastUpdated: Long = 0L
)

@Serializable
data class ItemGenre(
    val name: String
)

@Serializable
data class ItemDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null
)

@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null
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
    val displayArtist: String? = null,
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
data class ArtistInfo2(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val lastFmUrl: String? = null,
    val smallImageUrl: String? = null,
    val mediumImageUrl: String? = null,
    val largeImageUrl: String? = null,
    val similarArtist: List<Artist> = emptyList()
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
    val album: List<Album>
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
data class Directory(
    val id: String,
    val parent: String? = null,
    val name: String,
    val child: List<Song> = emptyList()
)

@Serializable
data class Indexes(
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
