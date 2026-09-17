package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.lwp2070809.speculonic.data.db.entities.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
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

    @Query("SELECT COUNT(*) FROM artists")
    fun getArtistsCountFlow(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM artists LIMIT 1) OR EXISTS(SELECT 1 FROM albums LIMIT 1)")
    suspend fun hasLocalData(): Boolean

    @Query("""
        UPDATE artists 
        SET albumCount = MAX(albumCount, (SELECT COUNT(*) FROM albums WHERE artistId = artists.id))
    """)
    suspend fun updateArtistAlbumCounts()
}
