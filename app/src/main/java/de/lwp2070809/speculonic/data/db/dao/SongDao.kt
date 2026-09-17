package de.lwp2070809.speculonic.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.db.entities.SongMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongsCount(): Int

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongsCountFlow(): Flow<Int>

    @Query("DELETE FROM songs WHERE albumId = :albumId AND id NOT IN (:ids)")
    suspend fun _deleteSongsByAlbumNotIn(albumId: String, ids: List<String>)

    @Transaction
    suspend fun deleteSongsByAlbumNotIn(albumId: String, ids: List<String>) {
        if (ids.isEmpty()) deleteSongsByAlbum(albumId) else _deleteSongsByAlbumNotIn(albumId, ids)
    }

    @Query("DELETE FROM songs WHERE albumId = :albumId")
    suspend fun deleteSongsByAlbum(albumId: String)

    @Query("DELETE FROM songs WHERE parent = :parentId AND id NOT IN (:ids)")
    suspend fun _deleteSongsByParentNotIn(parentId: String, ids: List<String>)

    @Transaction
    suspend fun deleteSongsByParentNotIn(parentId: String, ids: List<String>) {
        if (ids.isEmpty()) deleteSongsByParent(parentId) else _deleteSongsByParentNotIn(parentId, ids)
    }

    @Query("DELETE FROM songs WHERE parent = :parentId")
    suspend fun deleteSongsByParent(parentId: String)

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY track ASC")
    suspend fun getSongsByAlbum(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE parent = :parentId ORDER BY track ASC, title ASC")
    suspend fun getSongsByParent(parentId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE parent = :parentId ORDER BY track ASC, title ASC")
    fun getSongsByParentFlow(parentId: String): Flow<List<SongEntity>>

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

    @Query("UPDATE songs SET localUri = :localUri, isFullyCached = :isCached WHERE id = :songId")
    suspend fun updateSongCacheStatus(songId: String, localUri: String?, isCached: Boolean)

    @Query("UPDATE songs SET localUri = :localUri, isFullyCached = :isCached, isTranscoded = :isTranscoded WHERE id = :songId")
    suspend fun updateSongCacheStatus(songId: String, localUri: String?, isCached: Boolean, isTranscoded: Boolean)

    @Query("UPDATE songs SET isTranscoded = :isTranscoded WHERE id = :songId")
    suspend fun updateSongTranscodedStatus(songId: String, isTranscoded: Boolean)

    @Query("UPDATE songs SET localUri = :localUri WHERE id = :songId")
    suspend fun updateSongLocalUri(songId: String, localUri: String?)

    @Query("UPDATE songs SET isFullyCached = 0, localUri = NULL, isTranscoded = 0")
    suspend fun resetAllCacheStatus()

    @Query("SELECT id, localUri, isFullyCached, isTranscoded, starred, lastUpdated FROM songs")
    suspend fun getAllSongsMetadata(): List<SongMetadata>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdSync(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE title = :title AND artist = :artist LIMIT 1")
    suspend fun getSongByTitleAndArtist(title: String, artist: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdFlow(songId: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY track ASC")
    fun getSongsByAlbumFlow(albumId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    fun getStarredSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsPagingSource(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    suspend fun getAllCachedSongs(): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs WHERE isFullyCached = 1")
    suspend fun getCachedSongsCount(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE isFullyCached = 1")
    fun getCachedSongsCountFlow(): Flow<Int>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    fun getAllCachedSongsFlow(): Flow<List<SongEntity>>

    @Query("""
        SELECT localUri FROM songs 
        WHERE (coverArt = :id OR albumId = :id OR id = :id) 
        AND localUri IS NOT NULL 
        ORDER BY (coverArt = :id) DESC, (albumId = :id) DESC
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
}
