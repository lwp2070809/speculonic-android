package de.lwp2070809.speculonic.util

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Song

private const val DURATION_MILLIS_FACTOR = 1000L

private data class MediaItemMetadataSpec(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Int?,
    val coverArt: String?,
    val suffix: String?
)

@OptIn(UnstableApi::class)
private fun buildMediaItemInternal(
    spec: MediaItemMetadataSpec,
    repository: SubsonicRepository
): MediaItem {
    val playbackUri = repository.buildStreamUrl(spec.id, spec.suffix).toUri()
    val coverArtUrl = spec.coverArt?.let { repository.buildCoverArtUrl(it) }

    val rawDuration = spec.duration?.toLong() ?: 0L
    val durationMs = if (rawDuration <= 0L) {
        C.TIME_UNSET
    } else {
        rawDuration * DURATION_MILLIS_FACTOR
    }

    val extras = Bundle().apply {
        putString("coverArtId", spec.coverArt)
        putString("realTitle", spec.title)
        putString("realArtist", spec.artist)
        if (durationMs > 0L && durationMs != C.TIME_UNSET) {
            putLong("durationMs", durationMs)
        }
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(spec.title)
        .setArtist(spec.artist)
        .setAlbumTitle(spec.album)
        .setDurationMs(durationMs)
        .setArtworkUri(coverArtUrl?.toUri())
        .setExtras(extras)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(spec.id)
        .setUri(playbackUri)
        .setCustomCacheKey(spec.id)
        .setMediaMetadata(metadata)
        .build()
}

@OptIn(UnstableApi::class)
fun Song.toMediaItem(repository: SubsonicRepository): MediaItem {
    return buildMediaItemInternal(
        MediaItemMetadataSpec(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            coverArt = coverArt,
            suffix = suffix
        ),
        repository = repository
    )
}

@OptIn(UnstableApi::class)
fun SongEntity.toMediaItem(repository: SubsonicRepository): MediaItem {
    return buildMediaItemInternal(
        MediaItemMetadataSpec(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            coverArt = coverArt,
            suffix = suffix
        ),
        repository = repository
    )
}
