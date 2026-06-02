package de.lwp2070809.speculonic.domain.model

import de.lwp2070809.speculonic.data.db.entities.SongEntity

data class InconsistentItem(
    val id: String,
    val displayTitle: String,
    val type: Type,
    val localUri: String?,
    val dbSong: SongEntity?
) {
    enum class Type {
        MISSING_FILE,
        BINARY_MISMATCH,
        ORPHANED_FILE
    }
}
