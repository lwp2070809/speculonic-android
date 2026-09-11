@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import kotlinx.coroutines.CoroutineScope


class BluetoothCarManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val volumeCoordinator: VolumeCoordinator? = null,
    private val mediaSessionProvider: () -> MediaSession?,
    private val repositoryProvider: () -> SubsonicRepository?
) {
    private var isFullyInitialized = false

    val deviceDetector: BluetoothCarDeviceDetector = BluetoothCarDeviceDetector(context, serviceScope) {
        if (!isFullyInitialized) return@BluetoothCarDeviceDetector
        
        if (deviceDetector.isCarBluetoothConnected()) {
            (mediaSessionProvider()?.player as? CarDisguisePlayer)?.triggerCoverSync()
        }

        if (syncPlaybackState && deviceDetector.isCarBluetoothConnected()) {
            syncPlaybackStateToBluetooth()
        }
        
        if (bluetoothLyricsEnabled && deviceDetector.isCarBluetoothConnected()) {
            mediaSessionProvider()?.player?.currentMediaItem?.let {
                loadLyricsForBluetooth(it)
            }
        }
    }

    
    val lyricsManager = BluetoothLyricsManager(
        context = context,
        serviceScope = serviceScope,
        mediaSessionProvider = mediaSessionProvider,
        repositoryProvider = repositoryProvider,
        carConnectionState = deviceDetector.carConnectionState,
        isCarEnabled = { deviceDetector.carBluetoothEnabled },
        isLyricsEnabled = { bluetoothLyricsEnabled }
    )

    val stateSynchronizer = BluetoothStateSynchronizer(
        context = context,
        deviceDetector = deviceDetector,
        serviceScope = serviceScope,
        volumeCoordinator = volumeCoordinator,
        mediaSessionProvider = mediaSessionProvider,
        setJitterProtected = { lyricsManager.isPlayPauseJitterProtected = it }
    )

    

    var carBluetoothEnabled: Boolean
        get() = deviceDetector.carBluetoothEnabled
        set(value) { deviceDetector.carBluetoothEnabled = value }

    var syncPlaybackState: Boolean
        get() = stateSynchronizer.syncPlaybackState
        set(value) { stateSynchronizer.syncPlaybackState = value }

    var bluetoothCarDeviceNames: Set<String>
        get() = deviceDetector.bluetoothCarDeviceNames
        set(value) { deviceDetector.bluetoothCarDeviceNames = value }

    var bluetoothLyricsEnabled = false
    var bluetoothLyricsHideProgressBar = false

    

    init {
        isFullyInitialized = true
    }

    fun init() {
        deviceDetector.init()
    }

    fun release() {
        try {
            deviceDetector.release()
        } finally {
            lyricsManager.release()
        }
    }

    fun isCarBluetoothConnected(): Boolean {
        return deviceDetector.isCarBluetoothConnected()
    }

    fun syncPlaybackStateToBluetooth() {
        stateSynchronizer.syncPlaybackStateToBluetooth()
    }

    fun loadLyricsForBluetooth(mediaItem: MediaItem) {
        lyricsManager.loadLyricsForBluetooth(mediaItem)
    }

    fun startBluetoothLyricsUpdate() {
        lyricsManager.startBluetoothLyricsUpdate()
    }

    fun stopBluetoothLyricsUpdate() {
        lyricsManager.stopBluetoothLyricsUpdate()
    }

    fun updateMediaSessionMetadata(lyricLine: String?, forceUpdate: Boolean = false) {
        lyricsManager.updateMediaSessionMetadata(lyricLine, forceUpdate)
    }

    
    inner class CarDisguisePlayer(player: Player) : ForwardingPlayer(player) {
        private val listenerMap = java.util.concurrent.ConcurrentHashMap<Player.Listener, Player.Listener>()
        private val stateLock = Any()
        private var currentLyricTitle: String? = null
        private var currentLyricArtist: String? = null
        private var syncTimestamp: Long = 0L
        private var coverSyncTimestamp: Long = 0L

        override fun addListener(listener: Player.Listener) {
            val wrapped = DisguisedPlayerListener(this, listener)
            listenerMap[listener] = wrapped
            super.addListener(wrapped)
        }

        override fun removeListener(listener: Player.Listener) {
            val wrapped = listenerMap.remove(listener) ?: listener
            super.removeListener(wrapped)
        }

        override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
            val original = super.getMediaMetadata()
            var builder = original.buildUpon()
            var changed = false

            synchronized(stateLock) {
                if (currentLyricTitle != null) {
                    builder = builder.setTitle(currentLyricTitle)
                        .setArtist(currentLyricArtist ?: original.artist)
                    changed = true
                }

                if (syncTimestamp > 0) {
                    val extras = android.os.Bundle(original.extras ?: android.os.Bundle.EMPTY).apply {
                        putLong("_sync_timestamp", syncTimestamp)
                    }
                    builder = builder.setExtras(extras)
                    changed = true
                }

                if (coverSyncTimestamp > 0 && original.artworkUri != null) {
                    val uriStr = original.artworkUri.toString()
                    val separator = if (uriStr.contains("?")) "&" else "?"
                    val syncedUri = "$uriStr${separator}bt_sync_ts=$coverSyncTimestamp".toUri()
                    builder = builder.setArtworkUri(syncedUri)
                    changed = true
                }
            }

            return if (changed) builder.build() else original
        }

        override fun getCurrentMediaItem(): androidx.media3.common.MediaItem? {
            val original = super.getCurrentMediaItem() ?: return null
            var metaBuilder = original.mediaMetadata.buildUpon()
            var changed = false

            synchronized(stateLock) {
                if (currentLyricTitle != null) {
                    metaBuilder = metaBuilder.setTitle(currentLyricTitle)
                        .setArtist(currentLyricArtist ?: original.mediaMetadata.artist)
                    changed = true
                }

                if (syncTimestamp > 0) {
                    val extras = android.os.Bundle(original.mediaMetadata.extras ?: android.os.Bundle.EMPTY).apply {
                        putLong("_sync_timestamp", syncTimestamp)
                    }
                    metaBuilder = metaBuilder.setExtras(extras)
                    changed = true
                }

                if (coverSyncTimestamp > 0 && original.mediaMetadata.artworkUri != null) {
                    val uriStr = original.mediaMetadata.artworkUri.toString()
                    val separator = if (uriStr.contains("?")) "&" else "?"
                    val syncedUri = "$uriStr${separator}bt_sync_ts=$coverSyncTimestamp".toUri()
                    metaBuilder = metaBuilder.setArtworkUri(syncedUri)
                    changed = true
                }
            }

            return if (changed) {
                original.buildUpon().setMediaMetadata(metaBuilder.build()).build()
            } else {
                original
            }
        }

        fun updateBluetoothLyrics(title: String?, artist: String?) {
            synchronized(stateLock) {
                currentLyricTitle = title
                currentLyricArtist = artist
            }
            val newMetadata = mediaMetadata
            listenerMap.keys.forEach { it.onMediaMetadataChanged(newMetadata) }
        }

        fun triggerCoverSync() {
            synchronized(stateLock) {
                coverSyncTimestamp = System.currentTimeMillis()
            }
            val meta = mediaMetadata
            listenerMap.keys.forEach { it.onMediaMetadataChanged(meta) }
        }

        fun forceStateSync() {
            synchronized(stateLock) {
                syncTimestamp = System.currentTimeMillis()
            }
            val meta = mediaMetadata
            
            val state = playbackState
            val pwr = playWhenReady
            val reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            
            listenerMap.keys.forEach {
                it.onMediaMetadataChanged(meta)
                it.onPlaybackStateChanged(state)
                it.onPlayWhenReadyChanged(pwr, reason)
            }
        }

        private fun isHideProgressBarActive(): Boolean {
            return carBluetoothEnabled && bluetoothLyricsEnabled && bluetoothLyricsHideProgressBar && deviceDetector.carConnectionState.value
        }

        override fun getDuration(): Long {
            if (isHideProgressBarActive()) return C.TIME_UNSET
            val realDuration = super.getDuration()
            if (realDuration > 0 && realDuration != C.TIME_UNSET) {
                return realDuration
            }
            val metaDuration = currentMediaItem?.mediaMetadata?.durationMs
            if (metaDuration != null && metaDuration > 0 && metaDuration != C.TIME_UNSET) {
                return metaDuration
            }
            val extrasDuration = currentMediaItem?.mediaMetadata?.extras?.getLong("durationMs", 0L) ?: 0L
            if (extrasDuration > 0) {
                return extrasDuration
            }
            return realDuration
        }

        override fun isCurrentMediaItemSeekable(): Boolean {
            if (isHideProgressBarActive()) return false
            val isSeekable = super.isCurrentMediaItemSeekable()
            if (isSeekable) return true
            val metaDuration = currentMediaItem?.mediaMetadata?.durationMs ?: 0L
            return metaDuration > 0
        }

        override fun isCurrentMediaItemLive(): Boolean {
            return if (isHideProgressBarActive()) true else super.isCurrentMediaItemLive()
        }

        override fun isCurrentMediaItemDynamic(): Boolean {
            return if (isHideProgressBarActive()) true else super.isCurrentMediaItemDynamic()
        }

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            return if (isHideProgressBarActive()) {
                commands.buildUpon()
                    .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .build()
            } else {
                commands
            }
        }
    }
}

private class DisguisedPlayerListener(
    private val player: Player,
    private val target: Player.Listener
) : Player.Listener {
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        target.onMediaMetadataChanged(player.mediaMetadata)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        target.onMediaItemTransition(player.currentMediaItem, reason)
    }

    override fun onEvents(p: Player, events: Player.Events) {
        target.onEvents(player, events)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = target.onTimelineChanged(timeline, reason)
    override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = target.onTrackSelectionParametersChanged(parameters)
    override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = target.onAvailableCommandsChanged(availableCommands)
    override fun onPlaybackStateChanged(playbackState: Int) = target.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = target.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = target.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = target.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = target.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        target.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = target.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = target.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = target.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = target.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = target.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = target.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = target.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = target.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = target.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = target.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()
    override fun onCues(cueGroup: CueGroup) = target.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = target.onPlaylistMetadataChanged(mediaMetadata)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisguisedPlayerListener) return false
        return target == other.target
    }

    override fun hashCode(): Int = target.hashCode()

    override fun toString(): String = "DisguisedPlayerListener($target)"
}
