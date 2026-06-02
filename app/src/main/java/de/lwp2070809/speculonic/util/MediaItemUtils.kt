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
fun Song.toMediaItem(repository: SubsonicRepository): MediaItem {
    
    
    val playbackUri = repository.buildStreamUrl(this.id).toUri()
    
    val coverArtUrl = this.coverArt?.let { repository.buildCoverArtUrl(it) }

    val extras = Bundle().apply {
        putString("coverArtId", this@toMediaItem.coverArt)
        putString("realTitle", this@toMediaItem.title)
        putString("realArtist", this@toMediaItem.artist)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(this.title)
        .setArtist(this.artist)
        .setAlbumTitle(this.album)
        .setDurationMs(this.duration?.toLong()?.times(1000) ?: 0L)
        .setArtworkUri(coverArtUrl?.toUri())
        .setExtras(extras)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(this.id)
        .setUri(playbackUri)
        .setCustomCacheKey(this.id) 
        .setMediaMetadata(metadata)
        .build()
}

@OptIn(UnstableApi::class)
fun SongEntity.toMediaItem(repository: SubsonicRepository): MediaItem {
    val playbackUri = repository.buildStreamUrl(this.id).toUri()

    val coverArtUrl = this.coverArt?.let { repository.buildCoverArtUrl(it) }

    val extras = Bundle().apply {
        putString("coverArtId", this@toMediaItem.coverArt)
        putString("realTitle", this@toMediaItem.title)
        putString("realArtist", this@toMediaItem.artist)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(this.title)
        .setArtist(this.artist)
        .setAlbumTitle(this.album)
        .setDurationMs(this.duration?.toLong()?.times(1000) ?: 0L)
        .setArtworkUri(coverArtUrl?.toUri())
        .setExtras(extras)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(this.id)
        .setUri(playbackUri)
        .setCustomCacheKey(this.id)
        .setMediaMetadata(metadata)
        .build()
}
