package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity

@Dao
interface SyncTempDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncTempIds(ids: List<SyncTempIdEntity>)

    @Query("DELETE FROM sync_temp_ids")
    suspend fun clearSyncTempIds()

    @Query("DELETE FROM sync_temp_ids WHERE type = :type")
    suspend fun clearSyncTempIdsByType(type: String)

    @Query("SELECT COUNT(*) FROM sync_temp_ids WHERE type = :type")
    suspend fun getSyncTempIdCountByType(type: String): Int

    @Query("""
        SELECT id, 'song' AS type FROM songs WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'song')
        UNION ALL
        SELECT id, 'album' AS type FROM albums WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'album')
        UNION ALL
        SELECT id, 'artist' AS type FROM artists WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'artist')
    """)
    suspend fun getUnseenEntities(): List<SyncTempIdEntity>

    @Query("""
        SELECT id, localUri FROM songs WHERE id IN (:ids)
        AND id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'song')
    """)
    suspend fun getConfirmedSongCleanupTargets(ids: List<String>): List<SongCleanupTarget>

    @Query("""
        DELETE FROM albums WHERE id IN (:ids)
        AND id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'album')
        AND NOT EXISTS (SELECT 1 FROM songs WHERE albumId = albums.id)
    """)
    suspend fun deleteConfirmedAlbums(ids: List<String>)

    @Query("""
        DELETE FROM artists WHERE id IN (:ids)
        AND id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'artist')
        AND NOT EXISTS (SELECT 1 FROM albums WHERE artistId = artists.id)
        AND NOT EXISTS (SELECT 1 FROM songs WHERE artistId = artists.id)
    """)
    suspend fun deleteConfirmedArtists(ids: List<String>)
}
