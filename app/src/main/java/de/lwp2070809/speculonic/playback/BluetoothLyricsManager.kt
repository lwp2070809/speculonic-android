@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LyricLine
import de.lwp2070809.speculonic.util.LogManager
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
    private var lastUpdateTimestamp = 0L
    private var pendingUpdateJob: Job? = null

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
            
            val result = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                repo.getLyricsData(
                    mediaItem.mediaId,
                    realArtist ?: "",
                    realTitle ?: ""
                )
            }
            
            if (result == null) {
                LogManager.w("BluetoothLyricsManager: Fetch lyrics timed out for ${mediaItem.mediaId}")
                return@launch
            }
            
            val (raw, lines) = result
            
            if (lines.isEmpty() && raw.isNullOrBlank()) {
                val pureMusicStr = context.getString(R.string.pure_music)
                currentLyricsLines = listOf(LyricLine(timeMs = 0L, content = pureMusicStr))
                updateMediaSessionMetadata(pureMusicStr)
                return@launch
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

        val now = System.currentTimeMillis()
        val minInterval = 300L

        pendingUpdateJob?.cancel()

        if (forceUpdate || now - lastUpdateTimestamp >= minInterval) {
            lastUpdateTimestamp = now
            serviceScope.launch(Dispatchers.Main) {
                dispatchLyricsUpdate(player, lyricLine, realTitle, realArtist)
            }
        } else {
            val remainingDelay = minInterval - (now - lastUpdateTimestamp)
            pendingUpdateJob = serviceScope.launch(Dispatchers.Main) {
                delay(remainingDelay)
                lastUpdateTimestamp = System.currentTimeMillis()
                dispatchLyricsUpdate(player, lyricLine, realTitle, realArtist)
            }
        }
    }

    private fun dispatchLyricsUpdate(player: androidx.media3.common.Player, lyricLine: String?, realTitle: String, realArtist: String) {
        if (isCarEnabled() && isLyricsEnabled() && !lyricLine.isNullOrBlank()) {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(lyricLine, "$realTitle - $realArtist")
        } else {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(null, null)
        }
    }
}
