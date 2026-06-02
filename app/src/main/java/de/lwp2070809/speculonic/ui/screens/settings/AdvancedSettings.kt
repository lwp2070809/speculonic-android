package de.lwp2070809.speculonic.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.util.LogLevel
import de.lwp2070809.speculonic.util.LogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    var showLogViewer by remember { mutableStateOf(false) }
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).verticalScroll(rememberScrollState())) {


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

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = { showLogViewer = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.view_logs))
        }
    }

    if (showLogViewer) {
        LogViewerDialog(currentLogLevel = uiState.logLevel, onDismiss = { showLogViewer = false })
    }
}

@Composable
fun LogViewerDialog(currentLogLevel: LogLevel, onDismiss: () -> Unit) {
    val logs by LogManager.logs.collectAsState()
    val context = LocalContext.current
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var filterMenuExpanded by remember { mutableStateOf(false) }

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

    val filteredLogs = remember(logs, filterLevel) {
        if (filterLevel == null) logs else logs.filter { it.level == filterLevel }
    }

    val dialogContent = @Composable {
        Surface(
            modifier = Modifier.fillMaxSize(),
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
                        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        AsyncImage(
                            model = wallpaperRes,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth(if (isLandscape) 0.35f else 1f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.BottomCenter
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isSystemDark) Color.Black.copy(alpha = 0.6f) 
                                    else Color.White.copy(alpha = 0.5f)
                                )
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.logs), style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { filterMenuExpanded = true }) {
                                    Icon(
                                        Icons.Default.FilterList,
                                        contentDescription = "Filter"
                                    )
                                }
                                DropdownMenu(
                                    expanded = filterMenuExpanded,
                                    onDismissRequest = { filterMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("All Levels") },
                                        onClick = { filterLevel = null; filterMenuExpanded = false }
                                    )
                                    LogLevel.entries.forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text(level.name) },
                                            onClick = { filterLevel = level; filterMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                val text = LogManager.getAllLogsText()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Speculonic Logs", text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy All"
                                )
                            }
                            TextButton(onClick = { LogManager.clear() }) {
                                Text(stringResource(R.string.clear_logs))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().background(Color.Transparent).padding(horizontal = 8.dp),
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
                                "[${log.timestamp}] ${levelText}: ${log.message}"
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        dialogContent()
    }
}




