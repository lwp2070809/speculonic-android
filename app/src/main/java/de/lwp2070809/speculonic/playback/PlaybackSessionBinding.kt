package de.lwp2070809.speculonic.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/** Main-thread state belonging to the real player currently serving the media session. */
@UnstableApi
internal class PlaybackSessionBinding {
    private data class Attachment(
        val identity: Pair<String, String>,
        val player: Player,
        val engine: ExoPlayer,
        val listener: Player.Listener
    )

    private var attached: Attachment? = null
    private var observedSkipSilence: Boolean? = null
    val player: Player? get() = attached?.player

    fun needsPlayer(identity: Pair<String, String>): Boolean =
        identity.first.isNotBlank() && attached?.identity != identity

    fun attach(
        identity: Pair<String, String>,
        player: Player,
        engine: ExoPlayer,
        listener: Player.Listener,
        initialSkipSilence: Boolean
    ) {
        detach()
        engine.skipSilenceEnabled = observedSkipSilence ?: initialSkipSilence
        player.addListener(listener)
        attached = Attachment(identity, player, engine, listener)
    }

    fun detach(): Player? {
        val previous = attached ?: return null
        attached = null
        previous.player.removeListener(previous.listener)
        return previous.player
    }

    fun setSkipSilence(enabled: Boolean) {
        observedSkipSilence = enabled
        attached?.engine?.skipSilenceEnabled = enabled
    }
}
