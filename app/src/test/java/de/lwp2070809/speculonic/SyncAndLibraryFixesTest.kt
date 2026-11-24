package de.lwp2070809.speculonic

import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.data.prefs.ConnectionPrefsImpl
import de.lwp2070809.speculonic.domain.repository.MediaRepository
import de.lwp2070809.speculonic.domain.repository.PlaylistRepository
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.network.model.SubsonicAlbumResponse
import de.lwp2070809.speculonic.network.model.SubsonicResponse
import de.lwp2070809.speculonic.ui.screens.settings.ServerSyncViewModel
import de.lwp2070809.speculonic.util.MediaFormatUtils
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAndLibraryFixesTest {

    private fun testSong(
        id: String,
        suffix: String? = "mp3",
        starred: String? = null,
        isFullyCached: Boolean = false,
        isTranscoded: Boolean = false
    ): Song = Song(
        id = id,
        title = "Track $id",
        suffix = suffix,
        starred = starred,
        isFullyCached = isFullyCached,
        isTranscoded = isTranscoded
    )

    @Test
    fun a03AccountFingerprintNormalizesWhitespaceAndDiffersAcrossUsers() {
        val fpA = ConnectionPrefsImpl.computeAccountFingerprint("https://a.example.com ", " alice")
        val fpASame = ConnectionPrefsImpl.computeAccountFingerprint("https://a.example.com", "alice")
        val fpB = ConnectionPrefsImpl.computeAccountFingerprint("https://b.example.com", "bob")

        assertEquals(fpA, fpASame)
        assertNotEquals(fpA, fpB)
        assertEquals("", ConnectionPrefsImpl.computeAccountFingerprint("   ", ""))
    }

    @Test
    fun a03ResetDecisionKeepsLibraryForSameAccountAndResetsForDifferentOrMissingFingerprint() {
        val fpA = ConnectionPrefsImpl.computeAccountFingerprint("https://a.example.com", "alice")

        assertFalse(
            ServerSyncViewModel.shouldResetLocalDataForAccountChange(
                newUrl = "https://a.example.com",
                newUser = "alice",
                oldUrl = "",
                oldUser = "",
                oldFingerprint = fpA,
                hadResetWarning = true,
                hadLocalData = true
            )
        )
        assertTrue(
            ServerSyncViewModel.shouldResetLocalDataForAccountChange(
                newUrl = "https://b.example.com",
                newUser = "bob",
                oldUrl = "",
                oldUser = "",
                oldFingerprint = fpA,
                hadResetWarning = true,
                hadLocalData = true
            )
        )
        assertTrue(
            ServerSyncViewModel.shouldResetLocalDataForAccountChange(
                newUrl = "https://b.example.com",
                newUser = "bob",
                oldUrl = "",
                oldUser = "",
                oldFingerprint = null,
                hadResetWarning = true,
                hadLocalData = true
            )
        )
        assertFalse(
            ServerSyncViewModel.shouldResetLocalDataForAccountChange(
                newUrl = "https://a.example.com",
                newUser = "alice",
                oldUrl = "",
                oldUser = "",
                oldFingerprint = null,
                hadResetWarning = false,
                hadLocalData = false
            )
        )
    }

    @Test
    fun s05MissingSongCountAllowsMergeWithoutDetachingExistingAlbumSongs() {
        val response = Json.decodeFromString<SubsonicResponse<SubsonicAlbumResponse>>(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{
                "id":"album1","name":"Album","song":[{"id":"fx1","title":"Track fx1"},{"id":"fx2","title":"Track fx2"}]
            }}}"""
        )
        val album = requireNotNull(response.response.album)
        val songs = requireNotNull(album.song)
        assertNull(album.songCount)

        val omittedCount = MediaRepository.evaluateAlbumCompleteness(songCount = album.songCount, songs = songs)
        assertFalse(omittedCount.isKnownIncomplete)
        assertFalse(omittedCount.canDetachRemoved)

        val incompleteDeclared = MediaRepository.evaluateAlbumCompleteness(songCount = 3, songs = songs)
        assertTrue(incompleteDeclared.isKnownIncomplete)
        assertFalse(incompleteDeclared.canDetachRemoved)

        val completeDeclared = MediaRepository.evaluateAlbumCompleteness(songCount = 2, songs = songs)
        assertFalse(completeDeclared.isKnownIncomplete)
        assertTrue(completeDeclared.canDetachRemoved)

        val emptyDeclared = MediaRepository.evaluateAlbumCompleteness(songCount = 0, songs = emptyList())
        assertTrue(emptyDeclared.canDetachRemoved)
    }

    @Test
    fun l02DisallowedStreamingRestrictsQueueToCachedPlayableTracks() {
        val cachedTrack = testSong("fx1", isFullyCached = true)
        val uncachedTrack = testSong("fx2", isFullyCached = false)
        val cachedIncompatibleRaw = testSong("fx3", suffix = "alac", isFullyCached = true, isTranscoded = false)

        assertTrue(MediaFormatUtils.isSongPlayable(cachedTrack, transcodeIncompatible = false, isStreamingAllowed = false))
        assertFalse(MediaFormatUtils.isSongPlayable(uncachedTrack, transcodeIncompatible = false, isStreamingAllowed = false))
        assertFalse(MediaFormatUtils.isSongPlayable(cachedIncompatibleRaw, transcodeIncompatible = true, isStreamingAllowed = false))
        assertTrue(MediaFormatUtils.isSongPlayable(uncachedTrack, transcodeIncompatible = false, isStreamingAllowed = true))
    }

    @Test
    fun l03PlaylistSyncPreservesLocalStarWhenServerOmitsStarredField() {
        val localStarred = SongEntity(
            id = "fx1",
            title = "Track fx1",
            starred = true,
            lastUpdated = 12345L
        )
        val remoteEntryOmittingStar = testSong("fx1", starred = null)

        val merged = PlaylistRepository.toPlaylistSongEntity(remoteEntryOmittingStar, localStarred)
        assertTrue(merged.starred)
        assertEquals(12345L, merged.lastUpdated)
    }

    @Test
    fun l04PlaylistRemovalResolvesShiftedIndexAndGuardsAmbiguousDuplicates() {
        val selected = listOf(testSong("fx1"), testSong("fx2"), testSong("fx3"))
        val prepended = listOf(testSong("fx4"), testSong("fx1"), testSong("fx2"), testSong("fx3"))
        val duplicatedAfterChange = listOf(testSong("fx4"), testSong("fx2"), testSong("fx1"), testSong("fx2"))

        assertEquals(1, PlaylistRepository.resolvePlaylistRemoval(selected, selected, "fx2", 1))
        assertEquals(2, PlaylistRepository.resolvePlaylistRemoval(selected, prepended, "fx2", 1))
        assertNull(PlaylistRepository.resolvePlaylistRemoval(selected, duplicatedAfterChange, "fx2", 1))
        assertNull(PlaylistRepository.resolvePlaylistRemoval(selected, listOf(testSong("fx4"), testSong("fx1")), "fx2", 1))
    }
}
