package de.lwp2070809.speculonic.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val starred: Boolean = false,
    val created: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "album_list_items", primaryKeys = ["listType", "albumId"])
data class AlbumListItemEntity(
    val listType: String, 
    val albumId: String,
    val orderIndex: Int
)

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val parent: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val md5: String? = null,
    val isDir: Boolean = false,
    val isVideo: Boolean = false,
    val starred: Boolean = false,
    val localUri: String? = null, 
    val isFullyCached: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val `public`: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val pinned: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "playlist_song_cross_ref",
    indices = [Index(value = ["playlistId", "songId", "order"])]
)
data class PlaylistSongCrossRef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val songId: String,
    val order: Int
)

@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey val orderIndex: Int,
    val songId: String
)

@Entity(tableName = "sync_temp_ids", primaryKeys = ["id", "type"])
data class SyncTempIdEntity(
    val id: String,
    val type: String 
)

data class SongMetadata(
    val id: String,
    val localUri: String?,
    val isFullyCached: Boolean,
    val starred: Boolean,
    val lastUpdated: Long
)
