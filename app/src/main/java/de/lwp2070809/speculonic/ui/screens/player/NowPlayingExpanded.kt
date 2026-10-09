package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 平板电脑/横屏宽屏沉浸式播放器双栏布局。
 * 左栏呈现通用顶栏、大封面与完整控制底座（挂载 dragModifier 支持随手下拉折叠）；
 * 右栏呈现常驻沉浸式歌词卡片（保持独立滚动寻道，不干扰手势）。
 * 外部由 NowPlayingScaffold 统一驱动手势与模糊背景。
 */
@Composable
fun NowPlayingExpanded(
    playbackState: PlaybackState,
    uiState: NowPlayingUiState,
    playbackController: PlaybackController,
    viewModel: NowPlayingViewModel,
    isEffectivelyOnline: Boolean,
    onCollapse: () -> Unit,
    onShowQueue: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowSongInfo: () -> Unit,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // 左半栏（1f 权重）：顶栏、大唱片封面及主播放控制（支持下拉手势折叠）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 32.dp, vertical = 16.dp)
                .then(dragModifier),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部工具栏（共用组件，位于左半栏顶端）
            NowPlayingTopBar(
                title = playbackState.queueTitle,
                onCollapse = onCollapse,
                onShowQueue = onShowQueue
            )

            // 唱片大封面
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val cardSize = minOf(maxWidth, maxHeight)
                Box(
                    modifier = Modifier
                        .size(cardSize)
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    ArtworkView(
                        artworkId = playbackState.artworkId,
                        artworkUri = playbackState.artworkUri
                    )
                }
            }

            // 控制底座核心区
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SongInfo(playbackState)
                PlaybackSeekBar(playbackState, playbackController)
                MainControls(playbackState, playbackController, uiState, viewModel, onShowSongInfo)
                ExtraControls(playbackState, uiState, viewModel, isEffectivelyOnline, onShowSleepTimer)
            }
        }

        // 右半栏（1f 权重）：常驻沉浸式歌词卡片（保持纯净独立滚动与寻道，不挂载折叠手势）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(end = 32.dp, top = 16.dp, bottom = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(16.dp)
        ) {
            LyricsView(
                lyricsLines = uiState.lyricsLines,
                rawLyrics = uiState.rawLyrics,
                currentPosition = playbackState.currentPosition,
                isPlaying = playbackState.isPlaying,
                isLoading = uiState.isLoadingLyrics,
                onSeek = { playbackController.seekTo(it) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
