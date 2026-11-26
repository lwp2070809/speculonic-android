package de.lwp2070809.speculonic.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import de.lwp2070809.speculonic.data.db.entities.SearchSongState
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.db.entities.SongMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Upsert
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongsCount(): Int

    @Query("SELECT id FROM songs WHERE albumId = :albumId")
    suspend fun getSongIdsByAlbum(albumId: String): List<String>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIdsBatch(ids: List<String>)

    @Transaction
    suspend fun deleteSongsByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_IN_CHUNK_SIZE).forEach { deleteSongsByIdsBatch(it) }
    }

    @Query("UPDATE songs SET albumId = NULL WHERE albumId = :albumId AND id IN (:ids)")
    suspend fun detachAlbumSongs(albumId: String, ids: List<String>)

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1), COALESCE(track, 0), id")
    suspend fun getSongsByAlbum(albumId: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    suspend fun getStarredSongs(): List<SongEntity>

    @Query("UPDATE songs SET starred = :starred, lastUpdated = :lastUpdated WHERE id = :songId")
    suspend fun updateSongStarred(songId: String, starred: Boolean, lastUpdated: Long)

    @Query("SELECT id FROM songs WHERE starred = 1")
    suspend fun getStarredSongIds(): List<String>

    @Query("UPDATE songs SET starred = 0 WHERE id IN (:ids)")
    suspend fun unstarSongsByIdsBatch(ids: List<String>)

    @Transaction
    suspend fun unstarSongsByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_IN_CHUNK_SIZE).forEach { unstarSongsByIdsBatch(it) }
    }

    @Transaction
    suspend fun unstarSongsNotIn(ids: List<String>) {
        val currentStarred = getStarredSongIds()
        val toUnstar = currentStarred.toSet() - ids.toSet()
        if (toUnstar.isNotEmpty()) {
            unstarSongsByIds(toUnstar.toList())
        }
    }

    @Transaction
    suspend fun syncStarredSongs(songs: List<SongEntity>) {
        if (songs.isEmpty()) {
            clearAllSongStarredFlags()
        } else {
            unstarSongsNotIn(songs.map { it.id })
            songs.chunked(500).forEach { insertSongs(it) }
        }
    }

    @Query("UPDATE songs SET starred = 0")
    suspend fun clearAllSongStarredFlags()

    @Query("UPDATE songs SET localUri = :localUri, isFullyCached = :isCached WHERE id = :songId")
    suspend fun updateSongCacheStatus(songId: String, localUri: String?, isCached: Boolean)

    @Query("UPDATE songs SET localUri = :localUri, isFullyCached = :isCached, isTranscoded = :isTranscoded WHERE id = :songId")
    suspend fun updateSongCacheStatus(songId: String, localUri: String?, isCached: Boolean, isTranscoded: Boolean): Int

    @Query("UPDATE songs SET localUri = :localUri WHERE id = :songId")
    suspend fun updateSongLocalUri(songId: String, localUri: String?)

    @Query("UPDATE songs SET isFullyCached = 0, localUri = NULL, isTranscoded = 0")
    suspend fun resetAllCacheStatus()

    @Query(
        "UPDATE songs SET isFullyCached = 0, localUri = NULL, isTranscoded = 0 " +
            "WHERE localUri IS NULL OR localUri NOT LIKE 'content://%'"
    )
    suspend fun resetNonSafCacheStatus()

    @Query("SELECT id, localUri, isFullyCached, isTranscoded, starred, lastUpdated FROM songs")
    suspend fun getAllSongsMetadata(): List<SongMetadata>

    @Query("SELECT id, localUri, isFullyCached, isTranscoded, starred FROM songs WHERE id IN (:ids)")
    fun observeSearchSongMetadata(ids: List<String>): Flow<List<SearchSongState>>

    @Query("SELECT id, localUri, isFullyCached, isTranscoded, starred FROM songs WHERE id IN (:ids)")
    suspend fun getSearchSongMetadata(ids: List<String>): List<SearchSongState>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdSync(songId: String): SongEntity?

    @Query("SELECT * FROM songs WHERE title = :title AND artist = :artist LIMIT 1")
    suspend fun getSongByTitleAndArtist(title: String, artist: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIdsBatch(ids: List<String>): List<SongEntity>

    suspend fun getSongsByIds(ids: List<String>): List<SongEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_IN_CHUNK_SIZE).flatMap { getSongsByIdsBatch(it) }
    }

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdFlow(songId: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1), COALESCE(track, 0), id")
    fun getSongsByAlbumFlow(albumId: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE starred = 1 ORDER BY lastUpdated DESC, id ASC")
    fun getStarredSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsPagingSource(): PagingSource<Int, SongEntity>

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    suspend fun getAllCachedSongs(): List<SongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM songs WHERE localUri = :uri)")
    suspend fun hasSongAtUri(uri: String): Boolean

    @Query("SELECT COUNT(*) FROM songs WHERE isFullyCached = 1")
    suspend fun getCachedSongsCount(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE isFullyCached = 1")
    fun getCachedSongsCountFlow(): Flow<Int>

    @Query("SELECT * FROM songs WHERE isFullyCached = 1")
    fun getAllCachedSongsFlow(): Flow<List<SongEntity>>

    @Query("""
        SELECT localUri FROM (
            SELECT localUri, 1 AS priority FROM songs WHERE coverArt = :id AND localUri IS NOT NULL
            UNION ALL
            SELECT localUri, 2 AS priority FROM songs WHERE albumId = :id AND localUri IS NOT NULL
            UNION ALL
            SELECT localUri, 3 AS priority FROM songs WHERE id = :id AND localUri IS NOT NULL
        )
        ORDER BY priority ASC
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

    @Query("""
        SELECT * FROM songs
        WHERE title LIKE '%' || :escapedQuery || '%' ESCAPE '\' COLLATE NOCASE
           OR artist LIKE '%' || :escapedQuery || '%' ESCAPE '\' COLLATE NOCASE
           OR album LIKE '%' || :escapedQuery || '%' ESCAPE '\' COLLATE NOCASE
        ORDER BY isFullyCached DESC, title ASC
        LIMIT :limit
    """)
    suspend fun searchSongsLocal(escapedQuery: String, limit: Int): List<SongEntity>
}
