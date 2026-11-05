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
import kotlinx.coroutines.withTimeoutOrNull

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
    @Volatile private var currentLyricsLines: List<LyricLine> = emptyList()
    private var currentLyricsJob: Job? = null
    @Volatile var isPlayPauseJitterProtected = false
    @Volatile private var currentLoadedMediaId: String? = null

    fun release() {
        stopBluetoothLyricsUpdate()
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    fun loadLyricsForBluetooth(mediaItem: MediaItem) {
        if (mediaItem.mediaId == currentLoadedMediaId && currentLyricsLines.isNotEmpty()) {
            startBluetoothLyricsUpdate()
            return
        }
        val repo = repositoryProvider() ?: return
        
        currentLyricsLines = emptyList()
        currentLoadedMediaId = null
        updateMediaSessionMetadata(null)
        
        currentLyricsJob?.cancel()
        currentLyricsJob = serviceScope.launch {
            val metadata = mediaItem.mediaMetadata
            val realTitle = metadata.extras?.getString("realTitle") ?: metadata.title?.toString()
            val realArtist = metadata.extras?.getString("realArtist") ?: metadata.artist?.toString()
            
            val result = withTimeoutOrNull(6000L) {
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
                currentLoadedMediaId = mediaItem.mediaId
                updateMediaSessionMetadata(pureMusicStr)
                return@launch
            } else {
                currentLyricsLines = lines
                currentLoadedMediaId = mediaItem.mediaId
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
                    updateMediaSessionMetadata(null)
                    return@collectLatest
                }

                var lastLineIndex = -1
                val initialMediaId = player.currentMediaItem?.mediaId
                
                while (isActive) {
                    val lines = currentLyricsLines
                    val position = player.currentPosition
                    
                    val lineIndex = if (lines.isEmpty()) {
                        -1
                    } else {
                        lines.indexOfLast { it.timeMs <= position }
                    }
                    
                    if (lineIndex != lastLineIndex) {
                        val line = if (lineIndex != -1) lines[lineIndex].content else null
                        if (line != null || !isPlayPauseJitterProtected) {
                            updateMediaSessionMetadata(line)
                            lastLineIndex = lineIndex
                        }
                    }

                    val nextLineTime = if (lineIndex + 1 < lines.size) lines[lineIndex + 1].timeMs else -1L
                    val remainingMs = if (nextLineTime != -1L) nextLineTime - position else -1L

                    val delayTime = when {
                        !player.isPlaying -> 250L
                        remainingMs > 1000L -> 1000L
                        remainingMs > 15L -> remainingMs
                        remainingMs > 0L -> 15L
                        else -> 200L
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

    /**
     * 将歌词文本直接推送到系统 MediaSession。
     * 无内部去抖延迟，无丢弃式 Cancel，杜绝吞字。
     */
    fun updateMediaSessionMetadata(lyricLine: String?) {
        val session = mediaSessionProvider() ?: return
        val player = session.player
        val currentItem = player.currentMediaItem ?: return
        val originalMetadata = currentItem.mediaMetadata
        
        val realTitle = originalMetadata.extras?.getString("realTitle") ?: originalMetadata.title?.toString() ?: ""
        val realArtist = originalMetadata.extras?.getString("realArtist") ?: originalMetadata.artist?.toString() ?: ""

        serviceScope.launch(Dispatchers.Main) {
            dispatchLyricsUpdate(player, lyricLine, realTitle, realArtist)
        }
    }

    private fun dispatchLyricsUpdate(player: androidx.media3.common.Player, lyricLine: String?, realTitle: String, realArtist: String) {
        if (!carConnectionState.value && lyricLine != null) return
        if (isCarEnabled() && isLyricsEnabled() && !lyricLine.isNullOrBlank()) {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(lyricLine, "$realTitle - $realArtist")
        } else {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(null, null)
        }
    }
}
