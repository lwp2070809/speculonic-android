package de.lwp2070809.speculonic.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import de.lwp2070809.speculonic.util.LogTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalIsDualPane
import de.lwp2070809.speculonic.util.LogLevel
import de.lwp2070809.speculonic.util.LogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    var showLogViewer by remember { mutableStateOf(false) }
    val isDualPane = LocalIsDualPane.current
    val title = stringResource(R.string.advanced)

    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        topBarState.update(
            title = title,
            actions = {},
            showSearch = false,
            showBack = true,
            token = screenToken
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    // 在内嵌展示第三栏日志面板时，返回键优先关闭日志面板
    BackHandler(enabled = isDualPane && showLogViewer) {
        showLogViewer = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val canShowInlineThirdPane = isDualPane && maxWidth >= 580.dp
        val isInlineLogOpen = canShowInlineThirdPane && showLogViewer

        Row(modifier = Modifier.fillMaxSize()) {
            // 高级设置二级菜单（点击查看日志时调整宽度为 380.dp，为右侧第三栏腾出空间）
            Column(
                modifier = Modifier
                    .then(
                        if (isInlineLogOpen) Modifier.width(380.dp)
                        else Modifier.fillMaxWidth()
                    )
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(SettingsConstants.PAGE_PADDING)
                    .verticalScroll(rememberScrollState())
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
                )
            }
        }

        // 窄屏（手机）或空间不足模式下，使用全屏 Dialog 呈现与窄屏完全一致的日志界面
        if (showLogViewer && !canShowInlineThirdPane) {
            LogViewerDialog(
                currentLogLevel = uiState.logLevel,
                onDismiss = { showLogViewer = false }
            )
        }
    }
}

/**
 * 统一的查看日志面板组件，在大屏第三栏与窄屏全屏 Dialog 中共享完全一致的 UI 与交互逻辑。
 */
@Composable
fun LogViewerPane(
    currentLogLevel: LogLevel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logs by LogManager.logs.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var filterTag by remember { mutableStateOf<String?>(null) }
    var levelMenuExpanded by remember { mutableStateOf(false) }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    val isKaguya = currentLogLevel == LogLevel.KAGUYA
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()

    val wallpaperRes = remember {
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

    LaunchedEffect(Unit) {
        LogManager.flushIfDirty()
        while (true) {
            kotlinx.coroutines.delay(300)
            LogManager.flushIfDirty()
        }
    }

    val filteredLogs = remember(logs, filterLevel, filterTag) {
        logs.filter { entry ->
            (filterLevel == null || entry.level == filterLevel) &&
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
                                    contentDescription = stringResource(R.string.log_level)
                                )
                            }
                            DropdownMenu(
                                expanded = levelMenuExpanded,
                                onDismissRequest = { levelMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log_level_all)) },
                                    onClick = { filterLevel = null; levelMenuExpanded = false }
                                )
                                LogLevel.entries.forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(level.name) },
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
                                    contentDescription = stringResource(R.string.log_tag_all)
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
                                        text = { Text(label) },
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_logs)) },
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
                                                val file = LogManager.exportLogsToFile(context)
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
                                                    context.getString(R.string.share_logs)
                                                ).apply {
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    context.startActivity(chooser)
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.export_logs_failed, e.message ?: ""),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                )

                                // 复制全部文本
                                val logsCopiedMessage = stringResource(R.string.logs_copied_to_clipboard)
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
                                        val text = LogManager.getAllLogsText()
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Speculonic Logs", text)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, logsCopiedMessage, Toast.LENGTH_SHORT).show()
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
                        .background(Color.Transparent)
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredLogs) { log ->
                        val color = when (log.level) {
                            LogLevel.ERROR -> Color.Red
                            LogLevel.WARN -> Color(0xFFFFA500)
                            LogLevel.INFO -> MaterialTheme.colorScheme.primary
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.KAGUYA -> MaterialTheme.colorScheme.primary
                        }
                        val displayText = if (isKaguya && log.isEasterEgg) {
                            "[${log.timestamp}] ${log.message}"
                        } else {
                            val levelText = if (isKaguya && log.level == LogLevel.INFO) "月見 ヤチヨ" else log.level.name
                            "[${log.timestamp}] [${log.tag}/$levelText] ${log.message}"
                        }
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
fun LogViewerDialog(currentLogLevel: LogLevel, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LogViewerPane(
            currentLogLevel = currentLogLevel,
            onClose = onDismiss,
            modifier = Modifier.fillMaxSize()
        )
    }
}
