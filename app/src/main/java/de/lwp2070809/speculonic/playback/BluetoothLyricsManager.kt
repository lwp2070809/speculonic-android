@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package de.lwp2070809.speculonic.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    @Volatile private var currentLoadedMediaId: String? = null
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    fun release() {
        stopBluetoothLyricsUpdate()
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    fun loadLyricsForBluetooth(mediaItem: MediaItem) {
        if (mediaItem.mediaId == currentLoadedMediaId && currentLyricsLines.isNotEmpty()) {
            wakeSignal.trySend(Unit)
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

            wakeSignal.trySend(Unit)
            startBluetoothLyricsUpdate()
        }
    }

    fun startBluetoothLyricsUpdate() {
        if (!isCarEnabled() || !isLyricsEnabled()) return
        val sessionPlayer = mediaSessionProvider()?.player ?: return
        if (!sessionPlayer.playWhenReady) return

        bluetoothLyricsJob?.cancel()
        bluetoothLyricsJob = serviceScope.launch(Dispatchers.Main.immediate) {
            // 通过 Player.Listener 事件与下句精确倒计时协同唤醒，消除暂停与无下句歌词时的固定频率空转
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    wakeSignal.trySend(Unit)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    wakeSignal.trySend(Unit)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    wakeSignal.trySend(Unit)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    wakeSignal.trySend(Unit)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    wakeSignal.trySend(Unit)
                }
            }
            sessionPlayer.addListener(listener)
            try {
                carConnectionState.collectLatest { isConnected ->
                    if (!isConnected) {
                        updateMediaSessionMetadata(null)
                        return@collectLatest
                    }

                    var lastLineIndex = Int.MIN_VALUE
                    var lastDispatchedMediaId: String? = null
                    var keepRunning = true

                    while (isActive && keepRunning) {
                        val activePlayer = mediaSessionProvider()?.player
                        if (activePlayer == null) {
                            keepRunning = false
                        } else {
                            val stateUpdate = dispatchLyricsStep(
                                activePlayer = activePlayer,
                                lastLineIndex = lastLineIndex,
                                lastDispatchedMediaId = lastDispatchedMediaId
                            )
                            lastLineIndex = stateUpdate.first
                            lastDispatchedMediaId = stateUpdate.second
                        }
                    }
                }
            } finally {
                sessionPlayer.removeListener(listener)
            }
        }
    }

    fun stopBluetoothLyricsUpdate() {
        bluetoothLyricsJob?.cancel()
        bluetoothLyricsJob = null
    }

    private suspend fun dispatchLyricsStep(
        activePlayer: Player,
        lastLineIndex: Int,
        lastDispatchedMediaId: String?
    ): Pair<Int, String?> {
        val currentMediaId = activePlayer.currentMediaItem?.mediaId
        if (currentMediaId == null || currentMediaId != currentLoadedMediaId) {
            wakeSignal.receive()
            return lastLineIndex to lastDispatchedMediaId
        }

        val updatedLineBaseline = if (currentMediaId != lastDispatchedMediaId) Int.MIN_VALUE else lastLineIndex
        val lines = currentLyricsLines
        val position = activePlayer.currentPosition
        val lineIndex = if (lines.isEmpty()) -1 else lines.indexOfLast { it.timeMs <= position }

        if (lineIndex != updatedLineBaseline) {
            val line = if (lineIndex != -1) lines[lineIndex].content else null
            updateMediaSessionMetadata(line)
        }

        val nextLineTime = if (lineIndex + 1 < lines.size) lines[lineIndex + 1].timeMs else -1L
        val remainingMs = if (nextLineTime != -1L) nextLineTime - position else -1L

        if (!activePlayer.isPlaying || remainingMs <= 0L) {
            wakeSignal.receive()
        } else {
            withTimeoutOrNull(remainingMs.coerceAtLeast(MIN_LYRICS_WAKE_DELAY_MS)) {
                wakeSignal.receive()
            }
        }
        return lineIndex to currentMediaId
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

        serviceScope.launch(Dispatchers.Main.immediate) {
            dispatchLyricsUpdate(player, lyricLine, realTitle, realArtist)
        }
    }

    private fun dispatchLyricsUpdate(
        player: Player,
        lyricLine: String?,
        realTitle: String,
        realArtist: String
    ) {
        if (!carConnectionState.value && lyricLine != null) return
        if (isCarEnabled() && isLyricsEnabled() && !lyricLine.isNullOrBlank()) {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(lyricLine, "$realTitle - $realArtist")
        } else {
            (player as? BluetoothCarManager.CarDisguisePlayer)?.updateBluetoothLyrics(null, null)
        }
    }

    private companion object {
        const val MIN_LYRICS_WAKE_DELAY_MS = 16L
    }
}
