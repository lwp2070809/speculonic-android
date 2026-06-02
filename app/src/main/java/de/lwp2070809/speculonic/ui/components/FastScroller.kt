package de.lwp2070809.speculonic.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lwp2070809.speculonic.ui.screens.library.ArtistListItem
import kotlinx.coroutines.launch

@Composable
fun FastScroller(
    listState: LazyListState,
    flatItems: List<ArtistListItem>,
    modifier: Modifier = Modifier
) {
    if (flatItems.isEmpty()) return

    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    
    var isDragging by remember { mutableStateOf(false) }
    var currentLetter by remember { mutableStateOf("") }
    var scrollerHeight by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val thumbHeight = 48.dp
    val thumbHeightPx = remember(density) { with(density) { thumbHeight.toPx() } }
    val bubbleHeight = 48.dp
    val bubbleHeightPx = remember(density) { with(density) { bubbleHeight.toPx() } }

    
    fun scrollList(y: Float) {
        dragY = y
        val progress = (y / scrollerHeight).coerceIn(0f, 1f)
        val targetIndex = (progress * (flatItems.size - 1)).toInt().coerceIn(0, flatItems.size - 1)
        val targetItem = flatItems.getOrNull(targetIndex)
        val letter = targetItem?.groupChar ?: ""
        
        if (letter != currentLetter) {
            currentLetter = letter
        }
        
        scrollJob?.cancel()
        scrollJob = coroutineScope.launch {
            listState.scrollToItem(targetIndex)
        }
    }

    
    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging || listState.isScrollInProgress) 0.8f else 0.22f,
        label = "thumbAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(64.dp) 
            .onGloballyPositioned { scrollerHeight = it.size.height }
            .pointerInput(flatItems) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    dragY = down.position.y
                    scrollList(down.position.y)
                    down.consume()
                    
                    var currentPointerId = down.id
                    do {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
                        val change = event.changes.find { it.id == currentPointerId }
                        
                        if (change == null || !change.pressed) {
                            val otherChange = event.changes.firstOrNull { it.pressed }
                            if (otherChange != null) {
                                currentPointerId = otherChange.id
                                dragY = otherChange.position.y
                                scrollList(otherChange.position.y)
                                otherChange.consume()
                            }
                        } else {
                            dragY = change.position.y
                            scrollList(change.position.y)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    
                    isDragging = false
                }
            }
    ) {
        
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .fillMaxHeight()
                .width(1.5.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        )

        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp)
                .offset { 
                    val y = if (isDragging) {
                        (dragY - thumbHeightPx / 2).coerceIn(0f, (scrollerHeight - thumbHeightPx).coerceAtLeast(0f))
                    } else {
                        val firstVisibleItem = listState.firstVisibleItemIndex
                        val totalItemsCount = flatItems.size
                        val progress = if (totalItemsCount > 1) firstVisibleItem.toFloat() / (totalItemsCount - 1) else 0f
                        progress * (scrollerHeight - thumbHeightPx).coerceAtLeast(0f)
                    }
                    IntOffset(0, y.toInt()) 
                }
                .width(8.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(4.dp))
                .alpha(thumbAlpha)
                .background(MaterialTheme.colorScheme.primary)
        )

        
        AnimatedVisibility(
            visible = isDragging && currentLetter.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .wrapContentWidth(unbounded = true)
                .padding(end = 64.dp) 
                .offset { 
                    val y = (dragY - bubbleHeightPx / 2).coerceIn(0f, (scrollerHeight - bubbleHeightPx).coerceAtLeast(0f))
                    IntOffset(0, y.toInt()) 
                }
        ) {
            Box(
                modifier = Modifier
                    .requiredWidth(72.dp) 
                    .height(bubbleHeight)
                    
                    .clip(
                        RoundedCornerShape(
                            topStart = 24.dp,
                            bottomStart = 24.dp,
                            topEnd = 8.dp,
                            bottomEnd = 8.dp
                        )
                    )
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentLetter,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 18.sp
                )
            }
        }
    }
}
