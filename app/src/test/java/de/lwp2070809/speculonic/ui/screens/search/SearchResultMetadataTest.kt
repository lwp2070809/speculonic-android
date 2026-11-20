package de.lwp2070809.speculonic.ui.screens.search

import de.lwp2070809.speculonic.data.db.entities.SearchSongState
import de.lwp2070809.speculonic.network.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResultMetadataTest {
    private val metadata = SearchResultMetadata()
    private val song = Song(id = "s", title = "Song", starred = "remote")
    private val row = SearchSongState(song.id, null, false, false, starred = false)

    private fun observe(current: Song, local: SearchSongState?, pending: Map<String, Boolean> = emptyMap()): Song =
        metadata.merge(listOf(current), setOf(song.id), listOfNotNull(local), pending).single()

    @Test
    fun firstObservationPreservesRemoteStarAndReturningAppliesLocalEdits() {
        assertEquals("remote", observe(song, row).starred)
        val starred = observe(song.copy(starred = null), row.copy(starred = true))
        assertNotNull(starred.starred)
        assertNull(observe(starred, row).starred)
    }

    @Test
    fun newlyCachedSongIsReconciledAndPendingUnstarWins() {
        val remote = observe(song.copy(starred = null), null)
        val local = row.copy(starred = true, isFullyCached = true, localUri = "file:/song")
        val cached = observe(remote, local)
        assertEquals(local.localUri, cached.localUri)
        assertNotNull(cached.starred)
        assertNull(observe(cached, local, mapOf(song.id to false)).starred)
    }
}
