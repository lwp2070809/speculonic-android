package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity

@Dao
interface SyncTempDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncTempIds(ids: List<SyncTempIdEntity>)

    @Query("DELETE FROM sync_temp_ids")
    suspend fun clearSyncTempIds()

    @Query("DELETE FROM sync_temp_ids WHERE type = :type")
    suspend fun clearSyncTempIdsByType(type: String)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'artist')")
    suspend fun deleteArtistsNotInTemp()

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'album')")
    suspend fun deleteAlbumsNotInTemp()

    @Query("DELETE FROM songs WHERE id NOT IN (SELECT id FROM sync_temp_ids WHERE type = 'song')")
    suspend fun deleteSongsNotInTemp()
}
