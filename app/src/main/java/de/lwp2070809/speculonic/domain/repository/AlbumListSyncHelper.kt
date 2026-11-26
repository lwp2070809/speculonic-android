package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.data.db.entities.AlbumListItemEntity
import de.lwp2070809.speculonic.data.db.entities.SyncTempIdEntity
import de.lwp2070809.speculonic.network.api.SubsonicService
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.ensureOk

internal class AlbumListSyncHelper(
    private val api: SubsonicService,
    private val musicDao: MusicDao,
    private val authManager: AuthManager,
    private val entityMapper: EntityMapper,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun fetchAndPersistPage(
        type: String,
        size: Int,
        offset: Int,
        skipInsert: Boolean
    ): List<Album> {
        val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
        val response = api.getAlbumList2(u, t, s, type = type, size = size, offset = offset)
        response.response.ensureOk()
        val list = response.response.albumList2?.album ?: emptyList()
        if (list.isNotEmpty() && !skipInsert) {
            saveAlbumListChunk(listType = type, albums = list, offset = offset)
        }
        return list
    }

    suspend fun syncAllAlbums(
        type: String = "alphabeticalByName",
        recordTempIds: Boolean = false
    ): List<String> {
        var offset = 0
        val seenAlbumIds = LinkedHashSet<String>()
        while (true) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
            val response = api.getAlbumList2(u, t, s, type = type, size = ALBUM_PAGE_SIZE, offset = offset)
            val albums = checkNotNull(response.response.ensureOk().albumList2) {
                "Missing albumList2 in response"
            }.album
            if (albums.isEmpty()) return seenAlbumIds.toList()
            val hasPaginationIssue = if (recordTempIds) {
                albums.any { !seenAlbumIds.add(it.id) }
            } else {
                !seenAlbumIds.addAll(albums.map { it.id })
            }
            if (hasPaginationIssue) {
                throw IncompletePaginationException("Overlapping album page at offset $offset")
            }
            saveAlbumListChunk(
                listType = type,
                albums = albums,
                offset = offset,
                recordTempIds = recordTempIds
            )
            offset += albums.size
        }
    }

    suspend fun mergeAlbums(albums: List<Album>): List<AlbumEntity> {
        val existing = musicDao.getAlbumsByIds(albums.map { it.id }).associateBy { it.id }
        return albums.map { album -> entityMapper.albumToEntity(album, existing[album.id]) }
    }

    private suspend fun saveAlbumListChunk(
        listType: String,
        albums: List<Album>,
        offset: Int = 0,
        recordTempIds: Boolean = false
    ) {
        val listItems = albums.mapIndexed { index, album -> AlbumListItemEntity(listType, album.id, index + offset) }

        runInTransaction {
            val albumEntities = mergeAlbums(albums)
            if (recordTempIds) {
                albums.chunked(BATCH_SIZE).forEach { chunk ->
                    musicDao.insertSyncTempIds(chunk.map { SyncTempIdEntity(it.id, "album") })
                }
            }
            if (offset == 0) {
                musicDao.updateAlbumList(listType, albumEntities, listItems)
            } else {
                albumEntities.chunked(BATCH_SIZE).forEach { musicDao.insertAlbums(it) }
                listItems.chunked(BATCH_SIZE).forEach { musicDao.insertAlbumListItems(it) }
            }
        }
    }

    private companion object {
        const val ALBUM_PAGE_SIZE = 500
        const val BATCH_SIZE = 500
    }
}
