package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.LyricLine
import kotlinx.coroutines.delay

@Composable
fun LyricsView(
    lyricsLines: List<LyricLine>,
    rawLyrics: String?,
    currentPosition: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserInteractionTime by remember { mutableLongStateOf(0L) }

    var wasDragged by remember { mutableStateOf(false) }
    
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

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val viewHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        
        LaunchedEffect(isDragged) {
            if (isDragged) {
                wasDragged = true
                lastUserInteractionTime = System.currentTimeMillis()
            } else if (wasDragged) {
                lastUserInteractionTime = System.currentTimeMillis()
                
                if (lyricsLines.isNotEmpty()) {
                    val visibleItems = listState.layoutInfo.visibleItemsInfo
                    if (visibleItems.isNotEmpty()) {
                        val focalPoint = (viewHeightPx * 0.33f).toInt()
                        val targetItem = visibleItems.find { item ->
                            item.offset <= focalPoint && (item.offset + item.size) >= focalPoint
                        } ?: visibleItems.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - focalPoint) }
                        
                        targetItem?.let {
                            if (it.index in lyricsLines.indices) {
                                onSeek(lyricsLines[it.index].timeMs)
                            }
                        }
                    }
                }
                wasDragged = false
            }
        }

        
        LaunchedEffect(currentLineIndex, viewHeightPx) {
            val currentTime = System.currentTimeMillis()
            if (lyricsLines.isNotEmpty() && !isDragged && (currentTime - lastUserInteractionTime > 3000)) {
                
                
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
                contentPadding = PaddingValues(top = maxHeight / 3, bottom = maxHeight / 2),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(
                    items = lyricsLines,
                    key = { _, line -> line.timeMs.hashCode() + line.content.hashCode() }
                ) { index, line ->
                    val isCurrent = index == currentLineIndex
                    
                    val color by animateColorAsState(
                        targetValue = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        label = "LyricColor"
                    )
                    
                    
                    val scale by animateFloatAsState(
                        targetValue = if (isCurrent) 1.1f else 0.9f,
                        label = "LyricScale"
                    )

                    
                    val alpha by animateFloatAsState(
                        targetValue = if (isCurrent) 1f else 0.4f,
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
                            .padding(vertical = 12.dp, horizontal = 24.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                    )
                }
            }
        }
    }
}
