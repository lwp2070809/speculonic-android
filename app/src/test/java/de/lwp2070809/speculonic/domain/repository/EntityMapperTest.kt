package de.lwp2070809.speculonic.domain.repository

import de.lwp2070809.speculonic.data.db.entities.AlbumEntity
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.DiscTitle
import de.lwp2070809.speculonic.network.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMapperTest {

    @Test
    fun testAlbumDiscTitles_roundTripSerialization() {
        val discTitles = listOf(
            DiscTitle(disc = 1, title = "Disc One: Dawn"),
            DiscTitle(disc = 2, title = "Disc Two: Dusk", coverArt = "cover_disc_2")
        )
        val album = Album(
            id = "album_1",
            name = "Test Album",
            discTitles = discTitles
        )

        // 转换为 Entity
        val entity = EntityMapper.albumToEntity(album)
        assertNotNull(entity.discTitlesJson)
        val jsonStr = entity.discTitlesJson ?: ""
        assertTrue(jsonStr.contains("Disc One: Dawn"))
        assertTrue(jsonStr.contains("Disc Two: Dusk"))

        // 从 Entity 还原为 Album
        val converted = EntityMapper.toAlbum(entity)
        assertEquals(2, converted.discTitles.size)
        assertEquals(1, converted.discTitles[0].disc)
        assertEquals("Disc One: Dawn", converted.discTitles[0].title)
        assertEquals(2, converted.discTitles[1].disc)
        assertEquals("Disc Two: Dusk", converted.discTitles[1].title)
        assertEquals("cover_disc_2", converted.discTitles[1].coverArt)
    }

    @Test
    fun testAlbumDiscTitles_preservesExistingWhenEmpty() {
        val existingEntity = AlbumEntity(
            id = "album_1",
            name = "Existing Album",
            discTitlesJson = """[{"disc":1,"title":"Persisted Title"}]"""
        )
        // 传入没有 discTitles 的简版 Album
        val album = Album(
            id = "album_1",
            name = "Updated Album",
            discTitles = emptyList()
        )

        val updatedEntity = EntityMapper.albumToEntity(album, existing = existingEntity)
        assertEquals(existingEntity.discTitlesJson, updatedEntity.discTitlesJson)

        val converted = EntityMapper.toAlbum(updatedEntity)
        assertEquals(1, converted.discTitles.size)
        assertEquals("Persisted Title", converted.discTitles[0].title)
    }

    @Test
    fun testAlbumDiscTitles_corruptedJsonFallback() {
        val corruptedEntity = AlbumEntity(
            id = "album_1",
            name = "Corrupted Album",
            discTitlesJson = "{invalid json content}"
        )
        val album = EntityMapper.toAlbum(corruptedEntity)
        assertTrue(album.discTitles.isEmpty())
    }

    @Test
    fun testSongDiscNumber_roundTrip() {
        val song = Song(
            id = "song_1",
            title = "Track 1",
            discNumber = 2,
            track = 5
        )
        val entity = EntityMapper.songToEntity(song)
        assertEquals(2, entity.discNumber)
        assertEquals(5, entity.track)

        val converted = EntityMapper.toSong(entity)
        assertEquals(2, converted.discNumber)
        assertEquals(5, converted.track)
    }
}
