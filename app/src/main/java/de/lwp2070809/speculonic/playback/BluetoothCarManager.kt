@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import kotlinx.coroutines.CoroutineScope


class BluetoothCarManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mediaSessionProvider: () -> MediaSession?,
    private val repositoryProvider: () -> SubsonicRepository?
) {
    
    val deviceDetector: BluetoothCarDeviceDetector = BluetoothCarDeviceDetector(context, serviceScope) {
        
        
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

        private inner class WrappedListener(
            private val delegate: Player.Listener
        ) : Player.Listener by delegate {
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                delegate.onMediaMetadataChanged(this@CarDisguisePlayer.mediaMetadata)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                delegate.onMediaItemTransition(this@CarDisguisePlayer.currentMediaItem, reason)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                delegate.onEvents(this@CarDisguisePlayer, events)
            }
        }

        override fun addListener(listener: Player.Listener) {
            val wrapped = WrappedListener(listener)
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
            return if (isHideProgressBarActive()) C.TIME_UNSET else super.getDuration()
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
