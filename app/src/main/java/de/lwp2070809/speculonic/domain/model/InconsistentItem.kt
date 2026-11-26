package de.lwp2070809.speculonic.domain.model

import de.lwp2070809.speculonic.data.db.entities.SongEntity

data class InconsistentItem(
    val id: String,
    val displayTitle: String,
    val type: Type,
    val localUri: String?,
    val dbSong: SongEntity?
) {
    val key: String get() = if (type == Type.ORPHANED_FILE) "file:$localUri" else "song:$id"
    val canRedownload: Boolean get() = type != Type.ORPHANED_FILE && dbSong != null

    enum class Type {
        MISSING_FILE,
        BINARY_MISMATCH,
        ORPHANED_FILE
    }
}
