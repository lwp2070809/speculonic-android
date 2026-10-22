package de.lwp2070809.speculonic.data.db.dao

import androidx.room.Dao
import androidx.room.Transaction
import de.lwp2070809.speculonic.data.db.entities.PlaylistEntity
import de.lwp2070809.speculonic.data.db.entities.PlaylistSongCrossRef
import de.lwp2070809.speculonic.data.db.entities.SongEntity

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

    @Transaction
    suspend fun syncPlaylistWithSongs(
        playlist: PlaylistEntity,
        songs: List<SongEntity>,
        refs: List<PlaylistSongCrossRef>
    ) {
        insertPlaylists(listOf(playlist))
        deletePlaylistSongs(playlist.id)
        if (songs.isNotEmpty()) {
            songs.chunked(500).forEach { insertSongs(it) }
        }
        if (refs.isNotEmpty()) {
            refs.chunked(500).forEach { insertPlaylistSongCrossRefs(it) }
        }
    }
}
