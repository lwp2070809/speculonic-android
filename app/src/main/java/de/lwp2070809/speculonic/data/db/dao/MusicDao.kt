package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.db.entities.SongMetadata
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    @Query("""
        SELECT id, name, coverArt, lastUpdated,
        MAX(albumCount, (SELECT COUNT(*) FROM albums WHERE artistId = artists.id)) AS albumCount 
        FROM artists WHERE id = :id
    """)
    suspend fun getArtistById(id: String): ArtistEntity?

    @Query("""
        SELECT id, name, coverArt, lastUpdated,
        MAX(albumCount, (SELECT COUNT(*) FROM albums WHERE artistId = artists.id)) AS albumCount 
        FROM artists ORDER BY name ASC
    """)
    suspend fun getArtists(): List<ArtistEntity>

    @Query("""
        SELECT id, name, coverArt, lastUpdated,
        MAX(albumCount, (SELECT COUNT(*) FROM albums WHERE artistId = artists.id)) AS albumCount 
        FROM artists ORDER BY name ASC
    """)
    fun getAllArtistsFlow(): Flow<List<ArtistEntity>>

    @Query("SELECT COUNT(*) FROM artists")
    suspend fun getArtistsCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM artists LIMIT 1) OR EXISTS(SELECT 1 FROM albums LIMIT 1)")
    suspend fun hasLocalData(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncTempIds(ids: List<SyncTempIdEntity>)

    @Query("DELETE FROM sync_temp_ids")
    suspend fun clearSyncTempIds()

    @Query("DELETE FROM sync_temp_ids WHERE type = :type")
    suspend fun clearSyncTempIdsByType(type: String)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'artist')")
    suspend fun deleteArtistsNotInTemp()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY lastUpdated DESC")
    suspend fun getAlbums(): List<AlbumEntity>

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumsCount(): Int

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongsCount(): Int

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'album')")
    suspend fun deleteAlbumsNotInTemp()

    @Query("SELECT * FROM albums ORDER BY name ASC")
    fun getAllAlbumsFlow(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun getAlbumsByIds(ids: List<String>): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbumListItems(items: List<AlbumListItemEntity>)

    @Query("DELETE FROM album_list_items WHERE listType = :listType")
    suspend fun deleteAlbumListItems(listType: String)

    @Transaction
    suspend fun updateAlbumList(listType: String, albums: List<AlbumEntity>, items: List<AlbumListItemEntity>) {
        insertAlbums(albums)
        deleteAlbumListItems(listType)
        insertAlbumListItems(items)
    }

    @Query("""
        SELECT albums.* FROM albums 
        INNER JOIN album_list_items ON albums.id = album_list_items.albumId 
        WHERE album_list_items.listType = :listType 
        ORDER BY album_list_items.orderIndex ASC
    """)
    suspend fun getAlbumsByListType(listType: String): List<AlbumEntity>

    @Query("""
        SELECT albums.* FROM albums 
        INNER JOIN album_list_items ON albums.id = album_list_items.albumId 
        WHERE album_list_items.listType = :listType 
        ORDER BY album_list_items.orderIndex ASC
    """)
    fun getAlbumsByListTypeFlow(listType: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    suspend fun getStarredAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    fun getStarredAlbumsFlow(): Flow<List<AlbumEntity>>

    @Query("UPDATE albums SET starred = 0 WHERE starred = 1 AND id NOT IN (:ids)")
    suspend fun unstarAlbumsNotIn(ids: List<String>)

    @Transaction
    suspend fun syncStarredAlbums(listType: String, albums: List<AlbumEntity>, items: List<AlbumListItemEntity>) {
        if (albums.isEmpty()) {
            clearAllAlbumStarredFlags()
        } else {
            unstarAlbumsNotIn(albums.map { it.id })
            updateAlbumList(listType, albums, items)
        }
    }

    @Query("UPDATE albums SET starred = 0")
    suspend fun clearAllAlbumStarredFlags()

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC")
    suspend fun getAlbumsByArtist(artistId: String): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbumById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE id = :id")
    fun getAlbumByIdFlow(id: String): Flow<AlbumEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'song')")
    suspend fun deleteSongsNotInTemp()

    @Query("DELETE FROM songs WHERE albumId = :albumId AND id NOT IN (:ids)")
    suspend fun deleteSongsByAlbumNotIn(albumId: String, ids: List<String>)

    @Query("DELETE FROM songs WHERE parent = :parentId AND id NOT IN (:ids)")
    suspend fun deleteSongsByParentNotIn(parentId: String, ids: List<String>)

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY track ASC")
    suspend fun getSongsByAlbum(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE parent = :parentId ORDER BY track ASC, title ASC")
    suspend fun getSongsByParent(parentId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE parent = :parentId ORDER BY track ASC, title ASC")
    fun getSongsByParentFlow(parentId: String): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("DELETE FROM playlists WHERE id NOT IN (:ids)")
    suspend fun deletePlaylistsNotIn(ids: List<String>)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistByIdFlow(id: String): Flow<PlaylistEntity?>

    @Query("UPDATE playlists SET pinned = :pinned WHERE id = :playlistId")
    suspend fun updatePlaylistPinned(playlistId: String, pinned: Boolean)

    @Query("SELECT * FROM playlists WHERE pinned = 1 ORDER BY name ASC")
    fun getPinnedPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongCrossRefs(refs: List<PlaylistSongCrossRef>)

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.`order` ASC
    """)
    suspend fun getSongsByPlaylist(playlistId: String): List<SongEntity>

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: String)

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    suspend fun getStarredSongs(): List<SongEntity>

    @Query("UPDATE songs SET starred = :starred, lastUpdated = :lastUpdated WHERE id = :songId")
    suspend fun updateSongStarred(songId: String, starred: Boolean, lastUpdated: Long)

    @Query("UPDATE songs SET starred = 0 WHERE starred = 1 AND id NOT IN (:ids)")
    suspend fun unstarSongsNotIn(ids: List<String>)

    @Transaction
    suspend fun syncStarredSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) {
            clearAllSongStarredFlags()
        } else {
            unstarSongsNotIn(songs.map { it.id })
            insertSongs(songs)
        }
    }

    @Query("UPDATE songs SET starred = 0")
    suspend fun clearAllSongStarredFlags()

    @Query("UPDATE albums SET starred = :starred, lastUpdated = :lastUpdated WHERE id = :albumId")
    suspend fun updateAlbumStarred(albumId: String, starred: Boolean, lastUpdated: Long)

    @Query("""
        DELETE FROM albums 
        WHERE starred = 0 
        AND id NOT IN (SELECT DISTINCT albumId FROM songs WHERE albumId IS NOT NULL) 
        AND id NOT IN (SELECT DISTINCT albumId FROM album_list_items)
    """)
    suspend fun deleteOrphanedAlbums()

    @Query("UPDATE songs SET localUri = :localUri, isFullyCached = :isCached WHERE id = :songId")
    suspend fun updateSongCacheStatus(songId: String, localUri: String?, isCached: Boolean)

    @Query("UPDATE songs SET localUri = :localUri WHERE id = :songId")
    suspend fun updateSongLocalUri(songId: String, localUri: String?)

    @Query("UPDATE songs SET isFullyCached = 0, localUri = NULL")
    suspend fun resetAllCacheStatus()

    @Query("SELECT id, localUri, isFullyCached, starred, lastUpdated FROM songs")
    suspend fun getAllSongsMetadata(): List<SongMetadata>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdSync(songId: String): SongEntity?

    @Query("""
        UPDATE albums 
        SET artistId = (SELECT artistId FROM songs WHERE albumId = albums.id AND artistId IS NOT NULL LIMIT 1)
        WHERE (artistId IS NULL OR artistId = '')
    """)
    suspend fun repairAlbumArtistIds()

    @Query("""
        UPDATE artists 
        SET albumCount = MAX(albumCount, (SELECT COUNT(*) FROM albums WHERE artistId = artists.id))
    """)
    suspend fun updateArtistAlbumCounts()

    @Transaction
    suspend fun repairAndCount() {
        repairAlbumArtistIds()
        updateArtistAlbumCounts()
    }

    @Query("SELECT * FROM songs WHERE title = :title AND artist = :artist LIMIT 1")
    suspend fun getSongByTitleAndArtist(title: String, artist: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdFlow(songId: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY track ASC")
    fun getSongsByAlbumFlow(albumId: String): Flow<List<SongEntity>>

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.`order` ASC
    """)
    fun getSongsByPlaylistFlow(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    fun getStarredSongsFlow(): Flow<List<SongEntity>>

    
    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsPagingSource(): androidx.paging.PagingSource<Int, SongEntity>

    
    @Query("SELECT * FROM songs ORDER BY title ASC LIMIT 3000")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    suspend fun getAllCachedSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    fun getAllCachedSongsFlow(): Flow<List<SongEntity>>

    @Query("""
        SELECT localUri FROM songs 
        WHERE (coverArt = :id OR albumId = :id OR id = :id) 
        AND localUri IS NOT NULL 
        LIMIT 1
    """)
    suspend fun findLocalUriByCoverArtId(id: String): String?

    @Query("""
        SELECT DISTINCT coverArt FROM (
            SELECT coverArt FROM artists WHERE coverArt IS NOT NULL
            UNION
            SELECT coverArt FROM albums WHERE coverArt IS NOT NULL
            UNION
            SELECT coverArt FROM songs WHERE coverArt IS NOT NULL
            UNION
            SELECT coverArt FROM playlists WHERE coverArt IS NOT NULL
        )
    """)
    suspend fun getAllUniqueCoverArtIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaybackQueue(queue: List<PlaybackQueueEntity>)

    @Query("DELETE FROM playback_queue")
    suspend fun clearPlaybackQueue()

    @Transaction
    suspend fun updatePlaybackQueue(queue: List<PlaybackQueueEntity>) {
        clearPlaybackQueue()
        insertPlaybackQueue(queue)
    }

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playback_queue ON songs.id = playback_queue.songId 
        ORDER BY playback_queue.orderIndex ASC
    """)
    suspend fun getPlaybackQueue(): List<SongEntity>

}
