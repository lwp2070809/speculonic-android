package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums ORDER BY lastUpdated DESC")
    suspend fun getAlbums(): List<AlbumEntity>

    @Query("SELECT COUNT(*) FROM albums")
    suspend fun getAlbumsCount(): Int

    @Query("SELECT COUNT(*) FROM albums")
    fun getAlbumsCountFlow(): Flow<Int>

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

    @Query("UPDATE albums SET starred = :starred, lastUpdated = :lastUpdated WHERE id = :albumId")
    suspend fun updateAlbumStarred(albumId: String, starred: Boolean, lastUpdated: Long)

    @Query("""
        DELETE FROM albums 
        WHERE starred = 0 
        AND id NOT IN (SELECT DISTINCT albumId FROM songs WHERE albumId IS NOT NULL) 
        AND id NOT IN (SELECT DISTINCT albumId FROM album_list_items)
    """)
    suspend fun deleteOrphanedAlbums()

    @Query("""
        UPDATE albums 
        SET artistId = (SELECT artistId FROM songs WHERE albumId = albums.id AND artistId IS NOT NULL LIMIT 1)
        WHERE (artistId IS NULL OR artistId = '')
    """)
    suspend fun repairAlbumArtistIds()
}
