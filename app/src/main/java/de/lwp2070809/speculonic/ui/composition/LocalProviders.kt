package de.lwp2070809.speculonic.ui.composition

import androidx.compose.runtime.staticCompositionLocalOf
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController

val LocalSubsonicRepository = staticCompositionLocalOf<SubsonicRepository> {
    error("No SubsonicRepository provided")
}

val LocalPlaybackController = staticCompositionLocalOf<PlaybackController> {
    error("No PlaybackController provided")
}
