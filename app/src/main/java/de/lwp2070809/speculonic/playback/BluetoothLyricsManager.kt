@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


class BluetoothLyricsManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val mediaSessionProvider: () -> MediaSession?,
    private val repositoryProvider: () -> SubsonicRepository?,
    private val carConnectionState: StateFlow<Boolean>,
    private val isCarEnabled: () -> Boolean,
    private val isLyricsEnabled: () -> Boolean
) {
    private var bluetoothLyricsJob: Job? = null
    private var currentLyricsLines: List<LyricLine> = emptyList()
    private var currentLyricsJob: Job? = null

    fun release() {
        stopBluetoothLyricsUpdate()
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    
    fun loadLyricsForBluetooth(mediaItem: MediaItem) {
        val repo = repositoryProvider() ?: return
        
        currentLyricsLines = emptyList()
        updateMediaSessionMetadata(null)
        
        currentLyricsJob?.cancel()
        currentLyricsJob = serviceScope.launch {
            val metadata = mediaItem.mediaMetadata
            val realTitle = metadata.extras?.getString("realTitle") ?: metadata.title?.toString()
            val realArtist = metadata.extras?.getString("realArtist") ?: metadata.artist?.toString()
            
            val (raw, lines) = repo.getLyricsData(
                mediaItem.mediaId,
                realArtist ?: "",
                realTitle ?: ""
            )
            
            if (lines.isEmpty() && raw.isNullOrBlank()) {
                val pureMusicStr = context.getString(R.string.pure_music)
                currentLyricsLines = listOf(LyricLine(timeMs = 0L, content = pureMusicStr))
            } else {
                currentLyricsLines = lines
            }
            
            startBluetoothLyricsUpdate()
        }
    }

    fun startBluetoothLyricsUpdate() {
        if (!isCarEnabled() || !isLyricsEnabled()) return
        val player = mediaSessionProvider()?.player ?: return
        if (!player.playWhenReady) return
        
        bluetoothLyricsJob?.cancel()
        bluetoothLyricsJob = serviceScope.launch {
            carConnectionState.collectLatest { isConnected ->
                if (!isConnected) {
                    updateMediaSessionMetadata(null, forceUpdate = true)
                    return@collectLatest
                }

                var lastLineIndex = -1
                var lastValidPosition = 0L
                val initialMediaId = player.currentMediaItem?.mediaId
                
                while (isActive) {
                    val rawPosition = player.currentPosition
                    val position = if (rawPosition == 0L && lastValidPosition > 1000L) {
                        lastValidPosition
                    } else {
                        lastValidPosition = rawPosition
                        rawPosition
                    }
                    
                    val lineIndex = currentLyricsLines.indexOfLast { it.timeMs <= position }
                    
                    if (lineIndex != lastLineIndex) {
                        val line = if (lineIndex != -1) currentLyricsLines[lineIndex].content else null
                        updateMediaSessionMetadata(line)
                        lastLineIndex = lineIndex
                    }

                    val nextLineTime = if (lineIndex + 1 < currentLyricsLines.size) {
                        currentLyricsLines[lineIndex + 1].timeMs
                    } else {
                        -1L
                    }

                    val delayTime = if (nextLineTime != -1L && nextLineTime > position) {
                        (nextLineTime - position).coerceIn(100L, 1000L)
                    } else {
                        500L
                    }

                    delay(delayTime)
                    
                    
                    if (player.currentMediaItem?.mediaId != initialMediaId) {
                        break
                    }
                }
            }
        }
    }

    fun stopBluetoothLyricsUpdate() {
        bluetoothLyricsJob?.cancel()
        bluetoothLyricsJob = null
    }

    
    fun updateMediaSessionMetadata(lyricLine: String?, forceUpdate: Boolean = false) {
        val session = mediaSessionProvider() ?: return
        val player = session.player
        val currentItem = player.currentMediaItem ?: return
        val originalMetadata = currentItem.mediaMetadata
        
        val realTitle = originalMetadata.extras?.getString("realTitle") ?: originalMetadata.title?.toString() ?: ""
        val realArtist = originalMetadata.extras?.getString("realArtist") ?: originalMetadata.artist?.toString() ?: ""

        val newMetadata = if (isCarEnabled() && isLyricsEnabled() && !lyricLine.isNullOrBlank()) {
            originalMetadata.buildUpon()
                .setTitle(lyricLine)
                .setArtist("$realTitle - $realArtist")
                .setDurationMs(player.duration.takeIf { it > 0 } ?: originalMetadata.durationMs)
                .build()
        } else {
            originalMetadata.buildUpon()
                .setTitle(realTitle)
                .setArtist(realArtist)
                .setDurationMs(player.duration.takeIf { it > 0 } ?: originalMetadata.durationMs)
                .build()
        }

        if (forceUpdate || newMetadata.title != originalMetadata.title || newMetadata.artist != originalMetadata.artist) {
            serviceScope.launch(Dispatchers.Main) {
                if (isCarEnabled() && isLyricsEnabled() && !lyricLine.isNullOrBlank()) {
                    (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(lyricLine, "$realTitle - $realArtist")
                } else {
                    (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(null, null)
                }
            }
        }
    }
}
