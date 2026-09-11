package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LyricsView(
    lyricsLines: List<LyricLine>,
    rawLyrics: String?,
    currentPosition: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isSeekingMode by remember { mutableStateOf(false) }

    // 切换歌曲或歌词刷新时重置寻道模式
    LaunchedEffect(lyricsLines) {
        isSeekingMode = false
    }

    val currentLineIndex = remember(lyricsLines, currentPosition) {
        if (lyricsLines.isEmpty()) {
            0
        } else {
            var index = lyricsLines.binarySearch { it.timeMs.compareTo(currentPosition) }
            if (index < 0) {
                index = -index - 2
            }
            if (index < 0) 0 else index
        }
    }

    val centerItemIndex by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf -1
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            visibleItems.minByOrNull { item ->
                val itemCenter = item.offset + item.size / 2
                kotlin.math.abs(itemCenter - viewportCenter)
            }?.index ?: -1
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onClick?.invoke()
            }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // 用户交互监听：仅响应用户真实拖拽操作及后续惯性滑动，杜绝程序跟唱自动滚动误触发寻道模式
        LaunchedEffect(listState) {
            var isUserInteracting = false
            var seekModeTimeoutJob: Job? = null

            snapshotFlow { isDragged to listState.isScrollInProgress }
                .collect { (dragged, scrolling) ->
                    if (dragged) {
                        // 用户手指正在拖拽屏幕
                        isUserInteracting = true
                        seekModeTimeoutJob?.cancel()
                        isSeekingMode = true
                    } else if (isUserInteracting && !scrolling) {
                        // 用户手指已松开且列表惯性滑动完全停止
                        isUserInteracting = false
                        seekModeTimeoutJob?.cancel()
                        seekModeTimeoutJob = launch {
                            delay(3000L)
                            isSeekingMode = false
                        }
                    }
                }
        }

        // 自动跟唱滚动：在歌词更新、播放行切换或退出寻道模式后平滑滚动对齐当前唱段
        LaunchedEffect(lyricsLines, currentLineIndex, isSeekingMode) {
            if (lyricsLines.isEmpty() || isSeekingMode || isDragged) return@LaunchedEffect
            if (currentLineIndex in lyricsLines.indices) {
                listState.animateScrollToItem(currentLineIndex, scrollOffset = 0)
            }
        }

        if (isLoading) {
            CircularProgressIndicator()
        } else if (lyricsLines.isEmpty()) {
            if (!rawLyrics.isNullOrEmpty()) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = rawLyrics,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                Text(stringResource(R.string.no_lyrics), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = maxHeight / 2 - 24.dp, bottom = maxHeight / 2),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    items = lyricsLines,
                    key = { index, line -> "${index}_${line.timeMs}" }
                ) { index, line ->
                    val isHighlighted = if (isSeekingMode) index == centerItemIndex else index == currentLineIndex
                    
                    val color by animateColorAsState(
                        targetValue = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        label = "LyricColor"
                    )
                    
                    val scaleState = animateFloatAsState(
                        targetValue = if (isHighlighted) 1.1f else 0.9f,
                        label = "LyricScale"
                    )

                    val alphaState = animateFloatAsState(
                        targetValue = if (isHighlighted) 1f else 0.4f,
                        label = "LyricAlpha"
                    )

                    Text(
                        text = line.content,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 22.sp,
                            lineHeight = (22 * 1.4f).sp,
                            fontWeight = FontWeight.Bold
                        ),
                        textAlign = TextAlign.Center,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onClick?.invoke()
                            }
                            .padding(vertical = 12.dp, horizontal = 24.dp)
                            .graphicsLayer {
                                scaleX = scaleState.value
                                scaleY = scaleState.value
                                this.alpha = alphaState.value
                            }
                    )
                }
            }

            // 方案 D：网易云/QQ 音乐经典中央瞄准线与播放跳播指示器
            AnimatedVisibility(
                visible = isSeekingMode && centerItemIndex in lyricsLines.indices,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                val centerLine = lyricsLines.getOrNull(centerItemIndex)
                if (centerLine != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                text = FormatUtils.formatDuration(centerLine.timeMs),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum"
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onSeek(centerLine.timeMs)
                                    isSeekingMode = false
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

