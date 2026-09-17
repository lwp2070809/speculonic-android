package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.data.db.entities.SongEntity

@Dao
interface PlaybackQueueDao {
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
