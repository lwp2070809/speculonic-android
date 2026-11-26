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
        existingAlbumsMap: Map<String, AlbumEntity>? = null,
        recordTempIds: Boolean = false
    ): List<String> {
        var offset = 0
        var hasMore = true
        val seenAlbumIds = LinkedHashSet<String>()
        while (hasMore) {
            val (u, t, s) = authManager.getAuthParams(forceRefresh = true)
            val response = api.getAlbumList2(u, t, s, type = type, size = ALBUM_PAGE_SIZE, offset = offset)
            val albums = checkNotNull(response.response.ensureOk().albumList2) {
                "Missing albumList2 in response"
            }.album
            if (albums.isEmpty()) {
                hasMore = false
            } else {
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
                    existingAlbumsMap = existingAlbumsMap,
                    recordTempIds = recordTempIds
                )
                offset += albums.size
                hasMore = albums.size >= ALBUM_PAGE_SIZE
            }
        }
        return seenAlbumIds.toList()
    }

    suspend fun saveAlbumListChunk(
        listType: String,
        albums: List<Album>,
        offset: Int = 0,
        existingAlbumsMap: Map<String, AlbumEntity>? = null,
        recordTempIds: Boolean = false
    ) {
        if (albums.isEmpty()) return
        val resolvedExistingMap = existingAlbumsMap ?: albums.map { it.id }
            .chunked(BATCH_SIZE)
            .flatMap { musicDao.getAlbumsByIds(it) }
            .associateBy { it.id }

        val albumEntities = ArrayList<AlbumEntity>(albums.size)
        val listItems = ArrayList<AlbumListItemEntity>(albums.size)
        albums.forEachIndexed { index, album ->
            val existing = resolvedExistingMap[album.id]
            albumEntities.add(entityMapper.albumToEntity(album, existing = existing, isStarred = album.starred != null))
            listItems.add(AlbumListItemEntity(listType, album.id, index + offset))
        }

        runInTransaction {
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
