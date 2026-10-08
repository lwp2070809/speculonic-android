package de.lwp2070809.speculonic.ui.composition

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import coil3.request.ImageRequest
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.ui.navigation.Navigator

interface CoverArtRequester {
    fun buildCoverArtRequest(
        id: String?,
        context: Context? = null,
        preferLocal: Boolean = true,
        size: Int? = null,
        crossfade: Boolean = true
    ): ImageRequest
}

fun CoverArtRequester.buildRequest(
    id: String?,
    preferLocal: Boolean = true,
    size: Int? = null,
    crossfade: Boolean = true
): ImageRequest = buildCoverArtRequest(id, null, preferLocal, size, crossfade)

fun interface MediaItemConverter {
    fun toMediaItem(song: Song): MediaItem
}

val LocalCoverArtRequester = staticCompositionLocalOf<CoverArtRequester> {
    error("No CoverArtRequester provided")
}

val LocalMediaItemConverter = staticCompositionLocalOf<MediaItemConverter> {
    error("No MediaItemConverter provided")
}

val LocalDownloadController = staticCompositionLocalOf<DownloadController> {
    error("No DownloadController provided")
}

val LocalPlaybackController = staticCompositionLocalOf<PlaybackController> {
    error("No PlaybackController provided")
}

val LocalNavigator = staticCompositionLocalOf<Navigator?> { null }

val LocalIsDualPane = staticCompositionLocalOf { false }
