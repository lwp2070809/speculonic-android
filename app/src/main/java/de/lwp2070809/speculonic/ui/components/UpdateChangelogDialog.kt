package de.lwp2070809.speculonic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import de.lwp2070809.speculonic.R
import kotlinx.coroutines.launch
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.roundToInt

/**
 * 应用更新日志模态框。
 *
 * 使用自定义 Dialog 配合受控高度与独立垂直滚动区，避免长篇 Markdown 更新日志在窄屏或横屏下被截断，
 * 并在内容超出可视区时在右侧展示可拖拽滚动条。
 */
@Composable
fun UpdateChangelogDialog(
    version: String,
    releaseNotes: String,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxDialogHeight = (configuration.screenHeightDp.dp * 0.84f).coerceAtLeast(320.dp)

    val fallbackNotes = stringResource(R.string.update_no_release_notes)
    val markdownContent = remember(releaseNotes, fallbackNotes) {
        releaseNotes.trim().ifEmpty { fallbackNotes }
    }
    val markdownState = rememberMarkdownState(markdownContent)
    val scrollState = rememberScrollState()
    val isScrollable = scrollState.maxValue > 0
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.update_available_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = AlertDialogDefaults.titleContentColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.update_available_subtitle, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isScrollable) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .onSizeChanged { viewportHeightPx = it.height }
                ) {
                    Markdown(
                        markdownState = markdownState,
                        colors = markdownColor(),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleLarge,
                            h2 = MaterialTheme.typography.titleMedium,
                            h3 = MaterialTheme.typography.titleSmall,
                            h4 = MaterialTheme.typography.titleSmall,
                            h5 = MaterialTheme.typography.labelLarge,
                            h6 = MaterialTheme.typography.labelMedium,
                            text = MaterialTheme.typography.bodyMedium,
                            paragraph = MaterialTheme.typography.bodyMedium,
                            ordered = MaterialTheme.typography.bodyMedium,
                            bullet = MaterialTheme.typography.bodyMedium,
                            list = MaterialTheme.typography.bodyMedium,
                            code = MaterialTheme.typography.bodySmall,
                            table = MaterialTheme.typography.bodySmall
                        ),
                        components = markdownComponents(
                            table = { model ->
                                ResponsiveMarkdownTable(
                                    content = model.content,
                                    node = model.node,
                                    style = model.typography.table
                                )
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(
                                start = 24.dp,
                                end = if (isScrollable) 22.dp else 24.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            )
                    )

                    if (isScrollable && viewportHeightPx > 0) {
                        VerticalScrollbar(
                            scrollState = scrollState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(20.dp)
                                .height(with(density) { viewportHeightPx.toDp() })
                        )
                    }
                }

                if (isScrollable) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onUpdateNow) {
                        Text(stringResource(R.string.update_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }

    val thumbAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.85f else if (scrollState.isScrollInProgress) 0.72f else 0.45f,
        label = "scrollbarThumbAlpha"
    )
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val thumbColor = if (isDragging) {
        MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = thumbAlpha)
    }

    val verticalPaddingPx = with(density) { 8.dp.toPx() }
    val endPaddingPx = with(density) { 6.dp.toPx() }
    val barWidthPx = with(density) { 4.dp.toPx() }
    val minThumbHeightPx = with(density) { 28.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(scrollState.maxValue) {
                detectTapGestures { offset ->
                    val maxValue = scrollState.maxValue
                    val trackHeight = (size.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
                    if (maxValue > 0) {
                        val fraction = ((offset.y - verticalPaddingPx) / trackHeight).coerceIn(0f, 1f)
                        scope.launch {
                            scrollState.animateScrollTo((fraction * maxValue).roundToInt())
                        }
                    }
                }
            }
            .pointerInput(scrollState.maxValue) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val maxValue = scrollState.maxValue
                        val viewportHeight = scrollState.viewportSize.toFloat()
                        val trackHeight = (size.height - verticalPaddingPx * 2f).coerceAtLeast(1f)
                        val totalContentHeight = viewportHeight + maxValue
                        if (maxValue > 0 && totalContentHeight > 0f) {
                            val thumbHeight = (trackHeight * (viewportHeight / totalContentHeight))
                                .coerceIn(minThumbHeightPx, trackHeight)
                            val scrollableTrack = (trackHeight - thumbHeight).coerceAtLeast(1f)
                            val targetFraction = ((change.position.y - verticalPaddingPx - thumbHeight / 2f) / scrollableTrack)
                                .coerceIn(0f, 1f)
                            scope.launch {
                                scrollState.scrollTo((targetFraction * maxValue).roundToInt())
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxValue = scrollState.maxValue
            val viewportHeight = scrollState.viewportSize.toFloat()
            if (maxValue <= 0 || viewportHeight <= 0f) return@Canvas

            val trackHeight = (size.height - verticalPaddingPx * 2f).coerceAtLeast(0f)
            if (trackHeight <= 0f) return@Canvas

            val barLeft = size.width - endPaddingPx - barWidthPx
            val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(barLeft, verticalPaddingPx),
                size = Size(barWidthPx, trackHeight),
                cornerRadius = cornerRadius
            )

            val totalContentHeight = viewportHeight + maxValue
            val thumbHeight = (trackHeight * (viewportHeight / totalContentHeight))
                .coerceIn(minThumbHeightPx, trackHeight)
            val maxThumbOffset = (trackHeight - thumbHeight).coerceAtLeast(0f)
            val scrollFraction = (scrollState.value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            val thumbTop = verticalPaddingPx + maxThumbOffset * scrollFraction

            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(barLeft, thumbTop),
                size = Size(barWidthPx, thumbHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}

/**
 * 自适应弹窗宽度的 Markdown 表格，避免 2~4 列表格在窄屏弹窗内出现隐式横向裁剪。
 */
@Composable
private fun ResponsiveMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle
) {
    val headerNode = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    if (columnCount !in 1..4) {
        MarkdownTable(content = content, node = node, style = style)
        return
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val headerBgColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (headerNode != null) {
                TableRowContent(
                    content = content,
                    rowNode = headerNode,
                    columnCount = columnCount,
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.background(headerBgColor)
                )
            }
            rowNodes.forEach { rowNode ->
                HorizontalDivider(color = borderColor)
                TableRowContent(
                    content = content,
                    rowNode = rowNode,
                    columnCount = columnCount,
                    style = style
                )
            }
        }
    }
}

@Composable
private fun TableRowContent(
    content: String,
    rowNode: ASTNode,
    columnCount: Int,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val cells = rowNode.children.filter { it.type == GFMTokenTypes.CELL }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (index in 0 until columnCount) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                val cellNode = cells.getOrNull(index)
                if (cellNode != null) {
                    MarkdownTableBasicText(
                        content = content,
                        cell = cellNode,
                        style = style,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
        }
    }
}

