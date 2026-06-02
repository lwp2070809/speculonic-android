
package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.data_management)

    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        topBarState.update(
            title = title,
            actions = {},
            showSearch = false,
            showBack = true,
            token = screenToken
        )
        viewModel.refreshCacheSize()
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    val context = LocalContext.current
    
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {
                de.lwp2070809.speculonic.util.LogManager.e("StorageSettings: Failed to take persistable URI permission", e)
            }

            
            
            
            try {
                val refreshIntent = Intent(context, de.lwp2070809.speculonic.playback.PlaybackService::class.java).apply {
                    action = "de.lwp2070809.speculonic.action.REFRESH_SAF_PERMISSION"
                    data = it
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                }
                context.startService(refreshIntent)
            } catch (e: Exception) {
                de.lwp2070809.speculonic.util.LogManager.w("StorageSettings: Failed to refresh SAF permission in PlaybackService", e)
            }

            viewModel.updateCacheLocation(it.toString())
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        
        Text(
            text = stringResource(R.string.metadata_sync),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.background_sync)) },
            supportingContent = { Text(stringResource(R.string.background_sync_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.backgroundSyncEnabled,
                    onCheckedChange = { viewModel.updateBackgroundSyncEnabled(it) }
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.force_full_sync)) },
            supportingContent = { Text(stringResource(R.string.force_full_sync_description)) },
            leadingContent = { 
                Icon(
                    if (uiState.isSyncing) Icons.Default.Refresh else Icons.Default.Sync, 
                    contentDescription = null,
                    modifier = if (uiState.isSyncing) Modifier.rotate(rotation) else Modifier
                ) 
            },
            modifier = Modifier.clickable(enabled = !uiState.isSyncing) { viewModel.requestForceSync() }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        
        Text(
            text = stringResource(R.string.playback_cache),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        
        var expandedCacheSize by remember { mutableStateOf(false) }
        val cacheSizes = listOf(
            512L * 1024 * 1024 to "512 MB",
            1024L * 1024 * 1024 to "1 GB",
            2L * 1024 * 1024 * 1024 to "2 GB",
            5L * 1024 * 1024 * 1024 to "5 GB",
            -1L to stringResource(R.string.unlimited)
        )
        val currentCacheSizeLabel = cacheSizes.find { it.first == uiState.maxCacheSize }?.second 
            ?: if (uiState.maxCacheSize == -1L) stringResource(R.string.unlimited) else "${uiState.maxCacheSize / (1024 * 1024)} MB"

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ExposedDropdownMenuBox(
                expanded = expandedCacheSize,
                onExpandedChange = { expandedCacheSize = !expandedCacheSize },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = currentCacheSizeLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.max_internal_cache)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCacheSize) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expandedCacheSize,
                    onDismissRequest = { expandedCacheSize = false }
                ) {
                    cacheSizes.forEach { (size, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.updateMaxCacheSize(size)
                                expandedCacheSize = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }

        ListItem(
            headlineContent = { Text(stringResource(R.string.clear_internal_cache)) },
            supportingContent = { Text(stringResource(R.string.used_space, uiState.internalCacheSize)) },
            trailingContent = {
                TextButton(
                    onClick = { viewModel.requestClearCache() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.clear))
                }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        
        Text(
            text = stringResource(R.string.persistent_storage),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.persistent_storage_saf)) },
            supportingContent = {
                Text(
                    text = if (uiState.cacheLocation.isEmpty()) {
                        stringResource(R.string.not_configured)
                    } else {
                        FormatUtils.simplifySafUri(uiState.cacheLocation)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                Row {
                    if (uiState.cacheLocation.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateCacheLocation("") }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.reset), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { directoryLauncher.launch(null) }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.configure_saf))
                    }
                }
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.silent_cache)) },
            supportingContent = { Text(stringResource(R.string.silent_cache_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.silentCacheEnabled,
                    onCheckedChange = { viewModel.updateSilentCacheEnabled(it) }
                )
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        
        Text(
            text = stringResource(R.string.maintenance_tools),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (uiState.cacheLocation.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.scan_local_files)) },
                supportingContent = { 
                    Column {
                        Text(stringResource(R.string.external_usage, uiState.externalCacheSize))
                        if (uiState.isScanning && uiState.syncProgress != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${uiState.syncProgress} (${uiState.syncPercentage ?: 0}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                leadingContent = { 
                    Icon(
                        if (uiState.isScanning) Icons.Default.Refresh else Icons.Default.Search, 
                        contentDescription = null,
                        modifier = if (uiState.isScanning) Modifier.rotate(rotation) else Modifier
                    ) 
                },
                trailingContent = {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(
                            progress = { (uiState.syncPercentage ?: 0).toFloat() / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                modifier = Modifier.clickable(enabled = !uiState.isScanning) { viewModel.scanLocalFiles() }
            )
        }

        
        ListItem(
            headlineContent = { Text(stringResource(R.string.verify_cache)) },
            supportingContent = { 
                Column {
                    Text(stringResource(R.string.verify_description))
                    if (uiState.isSyncing && uiState.syncProgress != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${uiState.syncProgress} (${uiState.syncPercentage ?: 0}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            leadingContent = {
                Icon(
                    if (uiState.isSyncing) Icons.Default.Refresh else Icons.Default.Sync,
                    contentDescription = null,
                    modifier = if (uiState.isSyncing) Modifier.rotate(rotation) else Modifier
                )
            },
            trailingContent = {
                if (uiState.isSyncing) {
                    CircularProgressIndicator(
                        progress = { (uiState.syncPercentage ?: 0).toFloat() / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            modifier = Modifier.clickable(enabled = !uiState.isSyncing) { viewModel.requestSyncWithServer() }
        )
    }
    }

    
    if (uiState.showForceSyncConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelForceSync() },
            title = { Text(stringResource(R.string.confirm_force_sync_title)) },
            text = { Text(stringResource(R.string.confirm_force_sync_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmForceSync() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelForceSync() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (uiState.showMobileSyncConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelMobileSync() },
            title = { Text(stringResource(R.string.mobile_data_warning)) },
            text = { Text(stringResource(R.string.mobile_verify_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.syncWithServer() }) {
                    Text(stringResource(R.string.verify_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelMobileSync() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (uiState.showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelClearCache() },
            title = { Text(stringResource(R.string.clear_internal_cache)) },
            text = { Text(stringResource(R.string.clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearCache() }) {
                    Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelClearCache() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (uiState.showSilentCacheConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDisableSilentCache() },
            title = { Text(stringResource(R.string.silent_cache_warning_title)) },
            text = { Text(stringResource(R.string.silent_cache_warning_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDisableSilentCache() }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDisableSilentCache() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (uiState.isInteractiveScanning) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        progress = { (uiState.interactiveScanProgress.toFloat() / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在扫描缓存",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.interactiveScanStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (uiState.showInconsistencyDialog) {
        InconsistencyDialog(
            inconsistentItems = uiState.inconsistentItems,
            onDismissRequest = { viewModel.dismissInconsistencyDialog() },
            onResolveItem = { item, action -> viewModel.resolveInconsistentItem(item, action) }
        )
    }
}

