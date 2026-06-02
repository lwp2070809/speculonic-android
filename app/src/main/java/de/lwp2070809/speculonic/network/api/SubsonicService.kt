package de.lwp2070809.speculonic.network.api

import de.lwp2070809.speculonic.network.model.SubsonicAlbumListResponse
import de.lwp2070809.speculonic.network.model.SubsonicAlbumResponse
import de.lwp2070809.speculonic.network.model.SubsonicArtistInfo2Response
import de.lwp2070809.speculonic.network.model.SubsonicArtistResponse
import de.lwp2070809.speculonic.network.model.SubsonicBaseResponse
import de.lwp2070809.speculonic.network.model.SubsonicDirectoryResponse
import de.lwp2070809.speculonic.network.model.SubsonicIndexesResponse
import de.lwp2070809.speculonic.network.model.SubsonicLyricsListResponse
import de.lwp2070809.speculonic.network.model.SubsonicLyricsResponse
import de.lwp2070809.speculonic.network.model.SubsonicOpenSubsonicExtensionsResponse
import de.lwp2070809.speculonic.network.model.SubsonicPlaylistResponse
import de.lwp2070809.speculonic.network.model.SubsonicPlaylistsResponse
import de.lwp2070809.speculonic.network.model.SubsonicResponse
import de.lwp2070809.speculonic.network.model.SubsonicSearchResponse
import de.lwp2070809.speculonic.network.model.SubsonicSongResponse
import de.lwp2070809.speculonic.network.model.SubsonicStarred2Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SubsonicService {
    @GET("rest/ping")
    suspend fun ping(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    @GET("rest/getAlbumList2")
    suspend fun getAlbumList2(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json",
        @Query("type") type: String = "newest",
        @Query("size") size: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("musicFolderId") musicFolderId: String? = null
    ): SubsonicResponse<SubsonicAlbumListResponse>

    @GET("rest/getAlbum")
    suspend fun getAlbum(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicAlbumResponse>

    @GET("rest/getSong")
    suspend fun getSong(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicSongResponse>

    @GET("rest/getArtist")
    suspend fun getArtist(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicArtistResponse>

    @GET("rest/getArtistInfo2")
    suspend fun getArtistInfo2(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicArtistInfo2Response>

    @GET("rest/getPlaylists")
    suspend fun getPlaylists(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicPlaylistsResponse>

    @GET("rest/getPlaylist")
    suspend fun getPlaylist(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicPlaylistResponse>

    @GET("rest/getStarred2")
    suspend fun getStarred2(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicStarred2Response>

    @GET("rest/star")
    suspend fun star(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    @GET("rest/unstar")
    suspend fun unstar(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    @GET("rest/getMusicDirectory")
    suspend fun getMusicDirectory(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicDirectoryResponse>

    @GET("rest/getLyrics")
    suspend fun getLyrics(
        @Query("artist") artist: String?,
        @Query("title") title: String?,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicLyricsResponse>

    @GET("rest/getIndexes")
    suspend fun getIndexes(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json",
        @Query("musicFolderId") musicFolderId: String? = null
    ): SubsonicResponse<SubsonicIndexesResponse>

    @GET("rest/search3")
    suspend fun search3(
        @Query("query") query: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("artistCount") artistCount: Int = 20,
        @Query("albumCount") albumCount: Int = 20,
        @Query("songCount") songCount: Int = 100,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicSearchResponse>

    @GET("rest/search3")
    suspend fun search3Stream(
        @Query("query") query: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("artistCount") artistCount: Int = 20,
        @Query("albumCount") albumCount: Int = 20,
        @Query("songCount") songCount: Int = 100,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): okhttp3.ResponseBody

    @GET("rest/createPlaylist")
    suspend fun createPlaylist(
        @Query("name") name: String,
        @Query("songId") songId: List<String>? = null,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    @GET("rest/deletePlaylist")
    suspend fun deletePlaylist(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    @GET("rest/updatePlaylist")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("songIdToAdd") songIdToAdd: String? = null,
        @Query("songIndexToRemove") songIndexToRemove: Int? = null,
        @Query("name") name: String? = null,
        @Query("comment") comment: String? = null,
        @Query("public") public: Boolean? = null,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicBaseResponse>

    
    @GET("rest/getOpenSubsonicExtensions")
    suspend fun getOpenSubsonicExtensions(
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicOpenSubsonicExtensionsResponse>

    @GET("rest/getLyricsBySongId")
    suspend fun getLyricsBySongId(
        @Query("id") id: String,
        @Query("u") u: String,
        @Query("t") t: String,
        @Query("s") s: String,
        @Query("v") v: String = "1.16.1",
        @Query("c") c: String = "Speculonic",
        @Query("f") f: String = "json"
    ): SubsonicResponse<SubsonicLyricsListResponse>
}
