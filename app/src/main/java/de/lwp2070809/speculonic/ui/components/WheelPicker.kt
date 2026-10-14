package de.lwp2070809.speculonic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 通用垂直数字滚轮选择器（Wheel Picker）。
 *
 * 支持惯性滑动、居中吸附、无限循环滚动、触觉振动反馈与高斯淡入淡出视觉透视。
 *
 * @param items 数据列表
 * @param initialSelectedIndex 初始选中的真实列表索引
 * @param modifier 外部修饰符
 * @param visibleItemCount 可见项数量，默认展示 5 项（中心项 + 上下各 2 项）
 * @param itemHeight 单项高度
 * @param isInfinite 是否启用无限循环滚动
 * @param formatLabel 自定义显示标签格式
 * @param onSelectedIndexChanged 选中项变化回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> WheelPicker(
    items: List<T>,
    initialSelectedIndex: Int,
    modifier: Modifier = Modifier,
    visibleItemCount: Int = 5,
    itemHeight: Dp = 42.dp,
    isInfinite: Boolean = false,
    formatLabel: (T) -> String = { it.toString() },
    onSelectedIndexChanged: (Int) -> Unit
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val totalCount = if (isInfinite) items.size * 1000 else items.size
    val midOffset = if (isInfinite) (500 * items.size) else 0
    val safeInitialIndex = initialSelectedIndex.coerceIn(0, items.lastIndex)
    val startScrollIndex = midOffset + safeInitialIndex

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startScrollIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // 计算当前居中的 index
    val currentCenterItemIndex by remember {
        derivedStateOf {
            val firstIndex = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            if (firstOffset > itemHeightPx / 2f) {
                firstIndex + 1
            } else {
                firstIndex
            }
        }
    }

    // 将滚动的 centerIndex 映射到实际项的索引
    val selectedItemIndex by remember {
        derivedStateOf {
            if (isInfinite) {
                val mod = currentCenterItemIndex % items.size
                if (mod < 0) mod + items.size else mod
            } else {
                currentCenterItemIndex.coerceIn(0, items.lastIndex)
            }
        }
    }

    var lastHapticIndex by remember { mutableIntStateOf(selectedItemIndex) }

    LaunchedEffect(selectedItemIndex) {
        if (selectedItemIndex != lastHapticIndex) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticIndex = selectedItemIndex
            onSelectedIndexChanged(selectedItemIndex)
        }
    }

    val totalHeight = itemHeight * visibleItemCount
    val paddingItems = (visibleItemCount - 1) / 2
    val verticalPadding = itemHeight * paddingItems

    Box(
        modifier = modifier
            .height(totalHeight),
        contentAlignment = Alignment.Center
    ) {
        // 中心高亮选中槽位
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 4.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp)
                )
        )

        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = verticalPadding),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(
                count = totalCount,
                key = { it }
            ) { index ->
                val actualIndex = if (isInfinite) {
                    val mod = index % items.size
                    if (mod < 0) mod + items.size else mod
                } else {
                    index
                }

                val itemValue = items[actualIndex]
                val distanceFromCenter = abs(index - currentCenterItemIndex)

                val isCenter = distanceFromCenter == 0
                val (alpha, scale) = when (distanceFromCenter) {
                    0 -> 1.0f to 1.12f
                    1 -> 0.55f to 0.92f
                    else -> 0.25f to 0.80f
                }
                val fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal
                val textColor = if (isCenter) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(index)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatLabel(itemValue),
                        fontSize = 20.sp,
                        fontWeight = fontWeight,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
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
