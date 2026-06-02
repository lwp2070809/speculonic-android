package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.Song
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object EntityMapper {
    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun songToEntity(
        song: Song,
        albumId: String? = null,
        artistId: String? = null,
        localUri: String? = null,
        isCached: Boolean = false,
        parentId: String? = null,
        isStarred: Boolean? = null,
        lastUpdated: Long? = null
    ): SongEntity {
        val serverStarredTime = song.starred?.let { parseIsoTime(it) }
        val finalStarred = isStarred ?: (song.starred != null)
        
        return SongEntity(
            id = song.id,
            parent = parentId ?: song.parent,
            albumId = albumId ?: song.albumId ?: parentId ?: song.parent,
            artistId = artistId ?: song.artistId,
            title = song.title,
            album = song.album,
            artist = song.artist,
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
            isDir = song.isDir,
            isVideo = song.isVideo ?: false,
            starred = finalStarred,
            localUri = localUri,
            isFullyCached = isCached,
            lastUpdated = lastUpdated ?: serverStarredTime ?: System.currentTimeMillis()
        )
    }

    fun toSong(entity: SongEntity): Song {
        return Song(
            id = entity.id,
            parent = entity.parent,
            title = entity.title,
            album = entity.album,
            artist = entity.artist,
            albumId = entity.albumId,
            artistId = entity.artistId,
            track = entity.track,
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
            isDir = entity.isDir,
            isVideo = entity.isVideo,
            starred = if (entity.starred) formatIsoTime(entity.lastUpdated) else null,
            localUri = entity.localUri,
            isFullyCached = entity.isFullyCached
        )
    }

    fun albumToEntity(album: Album, existing: AlbumEntity? = null, isStarred: Boolean? = null): AlbumEntity {
        val serverStarredTime = album.starred?.let { parseIsoTime(it) }
        val finalStarred = isStarred ?: (album.starred != null)
        
        return AlbumEntity(
            id = album.id,
            name = album.name,
            artist = album.artist,
            artistId = album.artistId,
            coverArt = album.coverArt,
            songCount = album.songCount ?: existing?.songCount ?: 0,
            duration = album.duration ?: existing?.duration ?: 0,
            year = album.year ?: existing?.year,
            genre = album.genre ?: existing?.genre,
            starred = finalStarred,
            created = album.created ?: existing?.created,
            lastUpdated = serverStarredTime ?: existing?.lastUpdated ?: System.currentTimeMillis()
        )
    }

    fun toAlbum(entity: AlbumEntity, songs: List<Song> = emptyList()): Album {
        return Album(
            id = entity.id,
            name = entity.name,
            artist = entity.artist,
            artistId = entity.artistId,
            coverArt = entity.coverArt,
            songCount = entity.songCount,
            duration = entity.duration,
            year = entity.year,
            genre = entity.genre,
            starred = if (entity.starred) formatIsoTime(entity.lastUpdated) else null,
            created = entity.created,
            song = songs
        )
    }

    private fun parseIsoTime(isoString: String): Long? {
        return try {
            OffsetDateTime.parse(isoString, isoFormatter).toInstant().toEpochMilli()
        } catch (e: Exception) {
            
            null
        }
    }

    private fun formatIsoTime(millis: Long): String {
        return try {
            val odt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC)
            odt.format(isoFormatter)
        } catch (e: Exception) {
            "true"
        }
    }


    fun toPlaylist(entity: PlaylistEntity): Playlist {
        return Playlist(
            id = entity.id,
            name = entity.name,
            comment = entity.comment,
            owner = entity.owner,
            public = false,
            songCount = entity.songCount,
            duration = entity.duration,
            coverArt = entity.coverArt,
            pinned = entity.pinned
        )
    }
}
