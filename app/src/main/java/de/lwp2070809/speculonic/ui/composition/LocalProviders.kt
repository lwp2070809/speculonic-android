package de.lwp2070809.speculonic.ui.composition

import androidx.compose.runtime.compositionLocalOf
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController

val LocalSubsonicRepository = compositionLocalOf<SubsonicRepository> {
    error("No SubsonicRepository provided")
}

val LocalPlaybackController = compositionLocalOf<PlaybackController> {
    error("No PlaybackController provided")
}
