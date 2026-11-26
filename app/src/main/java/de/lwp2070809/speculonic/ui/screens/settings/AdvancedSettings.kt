package de.lwp2070809.speculonic.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.FileProvider
import de.lwp2070809.speculonic.util.LogTag
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalIsDualPane
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.screens.settings.components.BindSettingsTopBar
import de.lwp2070809.speculonic.util.LogLevel
import de.lwp2070809.speculonic.util.LogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettings(viewModel: GeneralSettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLogViewer by remember { mutableStateOf(false) }
    val isDualPane = LocalIsDualPane.current
    val title = stringResource(R.string.advanced)

    BindSettingsTopBar(topBarState = topBarState, title = title)

    var logBackProgress by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val canShowInlineThirdPane = isDualPane && maxWidth >= 640.dp
        val isInlineLogOpen = canShowInlineThirdPane && showLogViewer

        // 仅在内嵌展示第三栏日志面板时拦截预测性返回，避免与降级弹窗返回键冲突
        PredictiveBackHandler(enabled = isInlineLogOpen) { progressFlow ->
            try {
                progressFlow.collect { event ->
                    logBackProgress = event.progress
                }
                showLogViewer = false
            } finally {
                logBackProgress = 0f
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // 高级设置二级菜单（点击查看日志时调整宽度为 320.dp，为右侧第三栏腾出空间）
            Column(
                modifier = Modifier
                    .then(
                        if (isInlineLogOpen) Modifier.width(320.dp)
                        else Modifier.fillMaxWidth()
                    )
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = SettingsConstants.PAGE_PADDING,
                        end = SettingsConstants.PAGE_PADDING,
                        top = SettingsConstants.PAGE_PADDING,
                        bottom = SettingsConstants.PAGE_PADDING + LocalMiniPlayerBottomInset.current
                    )
            ) {
                var expandedLogLevel by remember { mutableStateOf(false) }
                val logLevels = LogLevel.entries

                ExposedDropdownMenuBox(
                    expanded = expandedLogLevel,
                    onExpandedChange = { expandedLogLevel = !expandedLogLevel },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = uiState.logLevel.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.log_level)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLogLevel) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedLogLevel,
                        onDismissRequest = { expandedLogLevel = false }
                    ) {
                        logLevels.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level.name) },
                                onClick = {
                                    viewModel.updateLogLevel(level)
                                    expandedLogLevel = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_EXTRA_LARGE))

                OutlinedButton(
                    onClick = { showLogViewer = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.view_logs))
                }
            }

            // 大屏模式下右侧第三栏：和窄屏模式下完全一致的查看日志界面
            if (isInlineLogOpen) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LogViewerPane(
                    currentLogLevel = uiState.logLevel,
                    onClose = { showLogViewer = false },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val scale = 1f - (logBackProgress * 0.08f)
                            scaleX = scale
                            scaleY = scale
                            translationX = logBackProgress * 48.dp.toPx()
                        }
                )
            }
        }

        // 窄屏（手机）或双栏详情区宽度不足时，使用自适应 LogViewerDialog 呈现日志界面
        if (showLogViewer && !canShowInlineThirdPane) {
            LogViewerDialog(
                currentLogLevel = uiState.logLevel,
                isExpandedDialog = isDualPane,
                onDismiss = { showLogViewer = false }
            )
        }
    }
}

/**
 * 统一的查看日志面板组件，在大屏第三栏与弹窗中共享完全一致的 UI 与交互逻辑。
 */
@Composable
fun LogViewerPane(
    currentLogLevel: LogLevel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // 仅在全屏 Dialog 模式下避让系统栏；大屏三栏内嵌与居中卡片弹窗不应重复叠加状态栏边距
    applySystemBarInsets: Boolean = false,
    applyMiniPlayerBottomInset: Boolean = !applySystemBarInsets
) {
    val logs by LogManager.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var filterTag by remember { mutableStateOf<String?>(null) }
    var levelMenuExpanded by remember { mutableStateOf(false) }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    val isKaguya = currentLogLevel == LogLevel.KAGUYA
    val isSystemDark = isSystemInDarkTheme()
    val layoutDirection = LocalLayoutDirection.current
    val headerInsetsModifier = if (applySystemBarInsets) {
        Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
    } else {
        Modifier
    }
    val listSafeInsets = if (applySystemBarInsets) {
        WindowInsets.safeDrawing
            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            .asPaddingValues()
    } else {
        PaddingValues(0.dp)
    }

    val wallpaperRes = remember(isKaguya) {
        if (isKaguya) {
            val wallpapers = listOf(
                R.drawable.kaguya_bg_1,
                R.drawable.kaguya_bg_2,
                R.drawable.yachiyo_bg_1,
                R.drawable.yachiyo_bg_2
            )
            wallpapers.random()
        } else {
            null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            LogManager.flushIfDirty()
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                LogManager.flushIfDirty()
            }
        }
    }

    val filteredLogs = remember(logs, filterLevel, filterTag) {
        logs.filter { entry ->
            (filterLevel == null || entry.level.priority >= filterLevel!!.priority) &&
            (filterTag == null || entry.tag == filterTag)
        }
    }

    Surface(
        modifier = modifier,
        color = if (isKaguya) Color.Transparent else MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isKaguya && wallpaperRes != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    AsyncImage(
                        model = wallpaperRes,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.BottomCenter
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (isSystemDark) Color.Black.copy(alpha = 0.65f)
                                else Color.White.copy(alpha = 0.55f)
                            )
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(headerInsetsModifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.logs),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 级别筛选下拉
                        Box {
                            IconButton(onClick = { levelMenuExpanded = true }) {
                                Icon(
                                    painterResource(id = R.drawable.ic_symbol_filter_list),
                                    contentDescription = stringResource(R.string.log_level),
                                    tint = if (filterLevel != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = levelMenuExpanded,
                                onDismissRequest = { levelMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.log_level_all),
                                            color = if (filterLevel == null) MaterialTheme.colorScheme.primary else Color.Unspecified
                                        )
                                    },
                                    onClick = { filterLevel = null; levelMenuExpanded = false }
                                )
                                LogLevel.entries.filter { it.isFilterable }.forEach { level ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = level.name,
                                                color = if (filterLevel == level) MaterialTheme.colorScheme.primary else Color.Unspecified
                                            )
                                        },
                                        onClick = { filterLevel = level; levelMenuExpanded = false }
                                    )
                                }
                            }
                        }

                        // 模块 Tag 筛选下拉
                        Box {
                            IconButton(onClick = { tagMenuExpanded = true }) {
                                Icon(
                                    painterResource(id = R.drawable.ic_symbol_dns),
                                    contentDescription = stringResource(R.string.log_tag_all),
                                    tint = if (filterTag != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = tagMenuExpanded,
                                onDismissRequest = { tagMenuExpanded = false }
                            ) {
                                val tagItems = listOf(
                                    null to stringResource(R.string.log_tag_all),
                                    LogTag.PLAYBACK to stringResource(R.string.log_tag_playback),
                                    LogTag.NETWORK to stringResource(R.string.log_tag_network),
                                    LogTag.SYNC to stringResource(R.string.log_tag_sync),
                                    LogTag.CACHE to stringResource(R.string.log_tag_cache),
                                    LogTag.APP to stringResource(R.string.log_tag_app)
                                )
                                tagItems.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                color = if (filterTag == tag) MaterialTheme.colorScheme.primary else Color.Unspecified
                                            )
                                        },
                                        onClick = { filterTag = tag; tagMenuExpanded = false }
                                    )
                                }
                            }
                        }

                        // 更多操作下拉菜单（分享、复制、清空）
                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options)
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false }
                            ) {
                                // 分享/导出完整日志文件
                                val shareLogsTitle = stringResource(R.string.share_logs)
                                DropdownMenuItem(
                                    text = { Text(shareLogsTitle) },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(id = R.drawable.ic_symbol_share),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val file = de.lwp2070809.speculonic.util.exportLogsToFile(context)
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    clipData = ClipData.newRawUri("", uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                val chooser = Intent.createChooser(
                                                    shareIntent,
                                                    shareLogsTitle
                                                ).apply {
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    context.startActivity(chooser)
                                                }
                                            } catch (e: Exception) {
                                                val errorText = context.applicationContext.getString(
                                                    R.string.export_logs_failed,
                                                    e.message ?: ""
                                                )
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        errorText,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                )

                                // 复制全部文本
                                val logsCopiedMessage = stringResource(R.string.logs_copied_to_clipboard)
                                val logsCopiedTruncatedMessage = stringResource(R.string.logs_copied_truncated_to_clipboard)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.content_description_copy_all)) },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(id = R.drawable.ic_symbol_content_copy),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        val (text, isTruncated) = LogManager.getRecentLogsForClipboard()
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Speculonic Logs", text)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(
                                            context,
                                            if (isTruncated) logsCopiedTruncatedMessage else logsCopiedMessage,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )

                                // 清空
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_logs)) },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(id = R.drawable.ic_symbol_delete),
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        LogManager.clear()
                                    }
                                )
                            }
                        }

                        // 关闭
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close)
                            )
                        }
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                    contentPadding = PaddingValues(
                        start = 8.dp + listSafeInsets.calculateStartPadding(layoutDirection),
                        top = 8.dp,
                        end = 8.dp + listSafeInsets.calculateEndPadding(layoutDirection),
                        bottom = 8.dp +
                            (if (applyMiniPlayerBottomInset) LocalMiniPlayerBottomInset.current else 0.dp) +
                            listSafeInsets.calculateBottomPadding()
                    )
                ) {
                    items(
                        items = filteredLogs,
                        key = { it.id },
                        contentType = { "log_item" }
                    ) { log ->
                        val color = when (log.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            LogLevel.INFO -> MaterialTheme.colorScheme.primary
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.KAGUYA -> MaterialTheme.colorScheme.primary
                        }
                        val displayText = log.format(isKaguya)
                        Text(
                            text = displayText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = color,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LogViewerDialog(
    currentLogLevel: LogLevel,
    onDismiss: () -> Unit,
    isExpandedDialog: Boolean = false
) {
    if (isExpandedDialog) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .widthIn(max = 600.dp)
                    .fillMaxHeight(0.88f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = SettingsConstants.DIALOG_TONAL_ELEVATION
            ) {
                LogViewerPane(
                    currentLogLevel = currentLogLevel,
                    onClose = onDismiss,
                    applySystemBarInsets = false,
                    applyMiniPlayerBottomInset = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        val isKaguya = currentLogLevel == LogLevel.KAGUYA
        val isSystemDark = isSystemInDarkTheme()
        val isLightSurface = if (isKaguya) {
            !isSystemDark
        } else {
            MaterialTheme.colorScheme.surface.luminance() > 0.5f
        }

        // Dialog 拥有独立 Window，需同步状态栏/导航栏图标对比度，避免浅色背景下白色图标不可见
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = isLightSurface
                controller.isAppearanceLightNavigationBars = isLightSurface
                window.isNavigationBarContrastEnforced = false
            }
        }

        LogViewerPane(
            currentLogLevel = currentLogLevel,
            onClose = onDismiss,
            applySystemBarInsets = true,
            applyMiniPlayerBottomInset = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}
