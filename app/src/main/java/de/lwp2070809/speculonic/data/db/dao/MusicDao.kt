package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Transaction

/**
 * 集中继承各领域数据访问接口，对现有代码保持完全向后兼容。
 */
@Dao
interface MusicDao :
    ArtistDao,
    AlbumDao,
    SongDao,
    PlaylistDao,
    PlaybackQueueDao,
    SyncTempDao {

    @Transaction
    suspend fun repairAndCount() {
        repairAlbumArtistIds()
        updateArtistAlbumCounts()
    }
}
