package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Upsert
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Transaction
    suspend fun deletePlaylistWithSongs(playlistId: String) {
        deletePlaylist(playlistId)
        deletePlaylistSongs(playlistId)
    }

    @Query("SELECT id FROM playlists")
    suspend fun getAllPlaylistIds(): List<String>

    @Query("DELETE FROM playlists WHERE id IN (:ids)")
    suspend fun deletePlaylistsByIdsBatch(ids: List<String>)

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId IN (:playlistIds)")
    suspend fun deletePlaylistSongsByPlaylistIdsBatch(playlistIds: List<String>)

    @Transaction
    suspend fun deletePlaylistsByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQLITE_IN_CHUNK_SIZE).forEach {
            deletePlaylistSongsByPlaylistIdsBatch(it)
            deletePlaylistsByIdsBatch(it)
        }
    }

    @Transaction
    suspend fun deletePlaylistsNotIn(ids: List<String>) {
        if (ids.isEmpty()) {
            clearAllPlaylists()
        } else {
            val localIds = getAllPlaylistIds()
            val toDelete = localIds.toSet() - ids.toSet()
            if (toDelete.isNotEmpty()) {
                deletePlaylistsByIds(toDelete.toList())
            }
        }
    }

    @Query("DELETE FROM playlist_song_cross_ref")
    suspend fun clearAllPlaylistSongCrossRefs()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylistsTable()

    @Transaction
    suspend fun clearAllPlaylists() {
        clearAllPlaylistSongCrossRefs()
        clearAllPlaylistsTable()
    }

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId NOT IN (SELECT id FROM playlists)")
    suspend fun deleteOrphanedPlaylistSongs()

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

    @Upsert
    suspend fun insertPlaylistSongCrossRefs(refs: List<PlaylistSongCrossRef>)

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.`order` ASC
    """)
    suspend fun getSongsByPlaylist(playlistId: String): List<SongEntity>

    @Query("""
        SELECT songs.* FROM songs 
        INNER JOIN playlist_song_cross_ref ON songs.id = playlist_song_cross_ref.songId 
        WHERE playlist_song_cross_ref.playlistId = :playlistId 
        ORDER BY playlist_song_cross_ref.`order` ASC
    """)
    fun getSongsByPlaylistFlow(playlistId: String): Flow<List<SongEntity>>

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun deletePlaylistSongs(playlistId: String)

    @Query("SELECT COUNT(*) FROM playlists")
    fun getPlaylistsCountFlow(): Flow<Int>
}
