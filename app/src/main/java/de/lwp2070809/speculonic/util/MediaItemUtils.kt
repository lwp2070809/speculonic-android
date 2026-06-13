package de.lwp2070809.speculonic.util

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Song

@OptIn(UnstableApi::class)
private fun buildMediaItemInternal(
    id: String,
    title: String,
    artist: String?,
    album: String?,
    duration: Int?,
    coverArt: String?,
    repository: SubsonicRepository
): MediaItem {
    val playbackUri = repository.buildStreamUrl(id).toUri()
    val coverArtUrl = coverArt?.let { repository.buildCoverArtUrl(it) }

    val extras = Bundle().apply {
        putString("coverArtId", coverArt)
        putString("realTitle", title)
        putString("realArtist", artist)
    }

    val rawDuration = duration?.toLong() ?: 0L
    val durationMs = if (rawDuration < 0) {
        androidx.media3.common.C.TIME_UNSET
    } else {
        rawDuration * 1000
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDurationMs(durationMs)
        .setArtworkUri(coverArtUrl?.toUri())
        .setExtras(extras)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(playbackUri)
        .setCustomCacheKey(id)
        .setMediaMetadata(metadata)
        .build()
}

@OptIn(UnstableApi::class)
fun Song.toMediaItem(repository: SubsonicRepository): MediaItem {
    return buildMediaItemInternal(
        id = this.id,
        title = this.title,
        artist = this.artist,
        album = this.album,
        duration = this.duration,
        coverArt = this.coverArt,
        repository = repository
    )
}

@OptIn(UnstableApi::class)
fun SongEntity.toMediaItem(repository: SubsonicRepository): MediaItem {
    return buildMediaItemInternal(
        id = this.id,
        title = this.title,
        artist = this.artist,
        album = this.album,
        duration = this.duration,
        coverArt = this.coverArt,
        repository = repository
    )
}
