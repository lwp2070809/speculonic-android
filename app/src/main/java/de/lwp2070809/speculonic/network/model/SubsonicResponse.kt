package de.lwp2070809.speculonic.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubsonicResponse<T>(
    @SerialName("subsonic-response")
    val response: T
)

interface BaseResponse {
    val status: String
    val version: String
    val type: String?
    val serverVersion: String?
    val openSubsonic: Boolean?
    val error: Error?
}

@Serializable
data class SubsonicBaseResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null
) : BaseResponse

@Serializable
data class SubsonicAlbumListResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val albumList2: AlbumList2Response? = null
) : BaseResponse

@Serializable
data class SubsonicAlbumResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val album: Album? = null
) : BaseResponse

@Serializable
data class SubsonicSongResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val song: Song? = null
) : BaseResponse

@Serializable
data class SubsonicArtistResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val artist: Artist? = null
) : BaseResponse

@Serializable
data class SubsonicArtistInfo2Response(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val artistInfo2: ArtistInfo2? = null
) : BaseResponse

@Serializable
data class SubsonicPlaylistsResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val playlists: PlaylistsResponse? = null
) : BaseResponse

@Serializable
data class SubsonicPlaylistResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val playlist: Playlist? = null
) : BaseResponse

@Serializable
data class SubsonicStarred2Response(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val starred2: Starred2Response? = null
) : BaseResponse

@Serializable
data class SubsonicDirectoryResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val directory: Directory? = null
) : BaseResponse

@Serializable
data class SubsonicLyricsResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val lyrics: Lyrics? = null
) : BaseResponse

@Serializable
data class SubsonicIndexesResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val indexes: Indexes? = null
) : BaseResponse

@Serializable
data class SubsonicSearchResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val searchResult3: SearchResult3? = null
) : BaseResponse

@Serializable
data class SubsonicOpenSubsonicExtensionsResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtension> = emptyList()
) : BaseResponse

@Serializable
data class SubsonicLyricsListResponse(
    override val status: String,
    override val version: String,
    override val type: String? = null,
    override val serverVersion: String? = null,
    override val openSubsonic: Boolean? = null,
    override val error: Error? = null,
    val lyricsList: LyricsListResponse? = null
) : BaseResponse

@Serializable
data class Error(
    val code: Int,
    val message: String
)
