package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.net.Uri
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import kotlin.math.roundToInt

/**
 * 正在播放页面的统一外壳容器。
 * 负责统一管理下拉折叠手势 (detectVerticalDragGestures)、弹簧位移动画、高斯模糊背景
 * 以及顶部居中拖拽胶囊把手 (Drag Handle)，消除跨设备形态下的重复技术债。
 */
@Composable
fun NowPlayingScaffold(
    artworkId: String?,
    artworkUri: Uri?,
    playerBackgroundMode: PlayerBackgroundMode,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (dragModifier: Modifier) -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary

    val gradientBrush = remember(primaryColor, surfaceColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.18f),
                primaryColor.copy(alpha = 0.06f),
                surfaceColor
            )
        )
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (isDragging) tween(0) else spring(),
        label = "dragOffset"
    )

    val currentOnCollapse by rememberUpdatedState(onCollapse)
    val density = LocalDensity.current

    val dragModifier = remember(density) {
        val collapseThresholdPx = with(density) { 150.dp.toPx() }
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { isDragging = true },
                onVerticalDrag = { change, dragAmount ->
                    dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                    change.consume()
                },
                onDragEnd = {
                    isDragging = false
                    if (dragOffset > collapseThresholdPx) {
                        currentOnCollapse()
                        dragOffset = 0f
                    } else {
                        dragOffset = 0f
                    }
                },
                onDragCancel = {
                    isDragging = false
                    dragOffset = 0f
                }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffset.roundToInt()) }
            .background(surfaceColor)
    ) {
        // 1. 高斯模糊背景
        PlayerBlurBackground(
            artworkId = artworkId,
            artworkUri = artworkUri,
            playerBackgroundMode = playerBackgroundMode
        )

        // 2. 主题渐变蒙层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        )

        // 3. 页面内容架构（含顶部胶囊指示条与内容插槽）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 遵循 M3 模态规范的顶部居中拖拽把手
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp, 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .clickable { onCollapse() }
                )
            }

            // 内容插槽区，分发 dragModifier 给非滚动子控件
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                content(dragModifier)
            }
        }
    }
}
