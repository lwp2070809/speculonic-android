package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.DiscTitle
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class SongEntityOverrides(
    val albumId: String? = null,
    val artistId: String? = null,
    val localUri: String? = null,
    val isCached: Boolean = false,
    val isTranscoded: Boolean? = null,
    val isStarred: Boolean? = null,
    val lastUpdated: Long? = null
)

object EntityMapper {
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun songToEntity(
        song: Song,
        overrides: SongEntityOverrides = SongEntityOverrides()
    ): SongEntity {
        require(song.id.isNotBlank()) { "Song ID cannot be blank" }
        val serverStarredTime = song.starred?.let { parseIsoTime(it) }
        val finalStarred = overrides.isStarred ?: (song.starred != null)

        return SongEntity(
            id = song.id,
            albumId = overrides.albumId ?: song.albumId,
            artistId = overrides.artistId ?: song.artistId,
            title = song.title,
            album = song.album,
            artist = song.artist,
            discNumber = song.discNumber,
            track = song.track,
            year = song.year,
            genre = song.genre,
            coverArt = song.coverArt,
            size = song.size,
            suffix = song.suffix,
            contentType = song.contentType,
            duration = song.duration,
            bitRate = song.bitRate,
            path = song.path,
            md5 = song.md5,
            isVideo = song.isVideo ?: false,
            starred = finalStarred,
            localUri = overrides.localUri,
            isFullyCached = overrides.isCached,
            isTranscoded = overrides.isTranscoded ?: song.isTranscoded,
            lastUpdated = overrides.lastUpdated ?: serverStarredTime ?: System.currentTimeMillis()
        )
    }

    fun toSong(entity: SongEntity): Song {
        return Song(
            id = entity.id,
            title = entity.title,
            album = entity.album,
            artist = entity.artist,
            albumId = entity.albumId,
            artistId = entity.artistId,
            track = entity.track,
            discNumber = entity.discNumber,
            year = entity.year,
            genre = entity.genre,
            coverArt = entity.coverArt,
            size = entity.size,
            suffix = entity.suffix,
            contentType = entity.contentType,
            duration = entity.duration,
            bitRate = entity.bitRate,
            path = entity.path,
            md5 = entity.md5,
            isVideo = entity.isVideo,
            starred = if (entity.starred) formatIsoTime(entity.lastUpdated) else null,
            localUri = entity.localUri,
            isFullyCached = entity.isFullyCached,
            isTranscoded = entity.isTranscoded
        )
    }

    fun albumToEntity(album: Album, existing: AlbumEntity? = null, isStarred: Boolean? = null): AlbumEntity {
        require(album.id.isNotBlank()) { "Album ID cannot be blank" }
        val serverStarredTime = album.starred?.let { parseIsoTime(it) }
        val finalStarred = isStarred ?: (album.starred != null)
        val discTitlesJson = if (album.discTitles.isNotEmpty()) {
            try {
                json.encodeToString(album.discTitles)
            } catch (e: Exception) {
                LogManager.w("EntityMapper: Failed to encode discTitles for album ${album.id}", e)
                existing?.discTitlesJson
            }
        } else {
            existing?.discTitlesJson
        }

        return AlbumEntity(
            id = album.id,
            name = album.name,
            artist = album.artist,
            artistId = album.artistId,
            coverArt = album.coverArt,
            songCount = album.songCount ?: existing?.songCount ?: 0,
            duration = album.duration?.toLong() ?: existing?.duration ?: 0L,
            year = album.year ?: existing?.year,
            genre = album.genre ?: existing?.genre,
            starred = finalStarred,
            created = album.created ?: existing?.created,
            discTitlesJson = discTitlesJson,
            lastUpdated = serverStarredTime ?: existing?.lastUpdated ?: System.currentTimeMillis()
        )
    }

    fun toAlbum(entity: AlbumEntity, songs: List<Song> = emptyList()): Album {
        val discTitles: List<DiscTitle> = entity.discTitlesJson?.let {
            try {
                json.decodeFromString<List<DiscTitle>>(it)
            } catch (e: Exception) {
                LogManager.w("EntityMapper: Failed to decode discTitles for album ${entity.id}", e)
                emptyList()
            }
        } ?: emptyList()

        return Album(
            id = entity.id,
            name = entity.name,
            artist = entity.artist,
            artistId = entity.artistId,
            coverArt = entity.coverArt,
            songCount = entity.songCount,
            duration = entity.duration.toInt(),
            year = entity.year,
            genre = entity.genre,
            starred = if (entity.starred) formatIsoTime(entity.lastUpdated) else null,
            created = entity.created,
            song = songs,
            discTitles = discTitles
        )
    }

    private fun parseIsoTime(isoString: String): Long? {
        return try {
            OffsetDateTime.parse(isoString, isoFormatter).toInstant().toEpochMilli()
        } catch (e: Exception) {
            LogManager.w("EntityMapper: Failed to parse ISO timestamp: $isoString", e)
            null
        }
    }

    private fun formatIsoTime(millis: Long): String {
        return try {
            val odt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
            odt.format(isoFormatter)
        } catch (e: Exception) {
            LogManager.w("EntityMapper: Failed to format timestamp: $millis", e)
            "true"
        }
    }


    fun toPlaylist(entity: PlaylistEntity): Playlist {
        return Playlist(
            id = entity.id,
            name = entity.name,
            comment = entity.comment,
            owner = entity.owner,
            `public` = entity.`public`,
            songCount = entity.songCount,
            duration = entity.duration.toInt(),
            coverArt = entity.coverArt,
            pinned = entity.pinned
        )
    }
}
