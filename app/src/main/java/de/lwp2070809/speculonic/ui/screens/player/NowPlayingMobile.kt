package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.ui.screens.player.components.ArtworkView
import de.lwp2070809.speculonic.ui.screens.player.components.ExtraControls
import de.lwp2070809.speculonic.ui.screens.player.components.MainControls
import de.lwp2070809.speculonic.ui.screens.player.components.NowPlayingTopBar
import de.lwp2070809.speculonic.ui.screens.player.components.PlaybackSeekBar
import de.lwp2070809.speculonic.ui.screens.player.components.SongInfo

/**
 * 手机移动端（窄屏）沉浸式播放器单栏布局。
 * 纯粹负责垂直流式排版：顶部工具栏、封面/歌词切换区及底部控制底座。
 * 外部由 NowPlayingScaffold 统一驱动手势与模糊背景。
 */
@Composable
fun NowPlayingMobile(
    playbackState: PlaybackState,
    uiState: NowPlayingUiState,
    playbackController: PlaybackController,
    viewModel: NowPlayingViewModel,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    isEffectivelyOnline: Boolean,
    onCollapse: () -> Unit,
    onShowQueue: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowSongInfo: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val scrimBrush = remember(surfaceColor) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.3f),
                surfaceColor.copy(alpha = 0.7f),
                surfaceColor
            )
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. 上半部分（占 2f）：顶部栏、大封面/歌词及歌曲基础信息
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部工具栏（共用组件）
                NowPlayingTopBar(
                    title = playbackState.queueTitle,
                    onCollapse = onCollapse,
                    onShowQueue = onShowQueue,
                    modifier = dragModifier
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 封面与歌词切换双向过渡区
                val lyricsAlpha by animateFloatAsState(
                    targetValue = if (showLyrics) 1f else 0f,
                    animationSpec = tween(durationMillis = 250),
                    label = "LyricsAlpha"
                )
                val artworkAlpha by animateFloatAsState(
                    targetValue = if (showLyrics) 0f else 1f,
                    animationSpec = tween(durationMillis = 250),
                    label = "ArtworkAlpha"
                )
                val lyricsScale by animateFloatAsState(
                    targetValue = if (showLyrics) 1f else 0.92f,
                    animationSpec = tween(durationMillis = 250),
                    label = "LyricsScale"
                )
                val artworkScale by animateFloatAsState(
                    targetValue = if (showLyrics) 1f else 1.05f,
                    animationSpec = tween(durationMillis = 250),
                    label = "ArtworkScale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(if (!showLyrics) dragModifier else Modifier)
                        .clickable { onToggleLyrics() }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = artworkAlpha
                                scaleX = artworkScale
                                scaleY = artworkScale
                            }
                    ) {
                        ArtworkView(
                            artworkId = playbackState.artworkId,
                            artworkUri = playbackState.artworkUri
                        )
                    }

                    if (lyricsAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = lyricsAlpha
                                    scaleX = lyricsScale
                                    scaleY = lyricsScale
                                }
                        ) {
                            if (uiState.isLoadingLyrics) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                LyricsView(
                                    lyricsLines = uiState.lyricsLines,
                                    rawLyrics = uiState.rawLyrics,
                                    currentPosition = playbackState.currentPosition,
                                    isPlaying = playbackState.isPlaying,
                                    isLoading = uiState.isLoadingLyrics,
                                    onSeek = { playbackController.seekTo(it) },
                                    onClick = onToggleLyrics,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 歌曲信息
                SongInfo(playbackState, modifier = dragModifier)
            }
        }

        // 2. 下半部分（占 1f）：进度条、主播放按钮组及扩展功能栏
        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .weight(1f)
                .background(scrimBrush)
                .then(dragModifier)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                PlaybackSeekBar(playbackState, playbackController)
                MainControls(playbackState, playbackController, uiState, viewModel, onShowSongInfo)
                ExtraControls(playbackState, uiState, viewModel, isEffectivelyOnline, onShowSleepTimer)
            }
        }
    }
}
