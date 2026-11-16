package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import de.lwp2070809.speculonic.data.db.entities.PlaybackQueueEntity
import de.lwp2070809.speculonic.data.db.entities.SongEntity

@Dao
interface PlaybackQueueDao {
    @Upsert
    suspend fun insertPlaybackQueue(queue: List<PlaybackQueueEntity>)

    @Query("SELECT id FROM songs WHERE id IN (:ids)")
    suspend fun queryExistingSongIdsBatch(ids: List<String>): List<String>

    @Query("DELETE FROM playback_queue")
    suspend fun clearPlaybackQueue()

    @Transaction
    suspend fun updatePlaybackQueue(queue: List<PlaybackQueueEntity>) {
        clearPlaybackQueue()
        if (queue.isEmpty()) return
        val existingIds = queue.map { it.songId }
            .distinct()
            .chunked(900)
            .flatMap { queryExistingSongIdsBatch(it) }
            .toHashSet()
        val validQueue = queue.filter { it.songId in existingIds }
        if (validQueue.isNotEmpty()) {
            validQueue.chunked(500).forEach { insertPlaybackQueue(it) }
        }
    }

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playback_queue ON songs.id = playback_queue.songId 
        ORDER BY playback_queue.orderIndex ASC
    """)
    suspend fun getPlaybackQueue(): List<SongEntity>
}
