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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.ui.screens.settings.components.BindSettingsTopBar
import de.lwp2070809.speculonic.ui.screens.settings.components.StorageDashboardCard

@Composable
private fun SpinningStorageRefreshIcon(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = null,
        modifier = modifier.graphicsLayer { rotationZ = rotation.value }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCacheSettings(
    viewModel: StorageCacheViewModel,
    topBarState: TopBarState,
    isEffectivelyOnline: Boolean
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.storage_cache_settings)
    BindSettingsTopBar(topBarState = topBarState, title = title)

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSize()
    }

    val context = LocalContext.current
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {
                de.lwp2070809.speculonic.util.LogManager.e("StorageCacheSettings: Failed to take persistable URI permission", e)
            }

            viewModel.updateCacheLocation(it.toString())
        }
    }

    val bottomInset = LocalMiniPlayerBottomInset.current

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SettingsConstants.PAGE_PADDING + bottomInset)
        ) {
            StorageDashboardCard(
                playbackBytes = uiState.playbackCacheBytes,
                coverArtBytes = uiState.coverArtCacheBytes,
                songBytes = uiState.songCacheBytes,
                totalSpaceBytes = uiState.totalSpaceBytes,
                maxCoverBytes = uiState.maxCoverCacheSize,
                isCoverOverQuota = uiState.isCoverOverQuota,
                isSafEnabled = uiState.cacheLocation.isNotEmpty(),
                playbackSizeLabel = uiState.playbackCacheSize,
                coverArtSizeLabel = uiState.coverArtCacheSize,
                songSizeLabel = uiState.songCacheSize,
                otherSizeLabel = uiState.otherCacheSize,
                onClearPlaybackClick = { viewModel.requestClearCache(CacheClearTarget.PLAYBACK) },
                onClearCoverArtClick = { viewModel.requestClearCache(CacheClearTarget.COVER_ART) },
                onClearSongsClick = { viewModel.requestClearCache(CacheClearTarget.SONGS) },
                modifier = Modifier.padding(
                    start = SettingsConstants.PAGE_PADDING,
                    end = SettingsConstants.PAGE_PADDING,
                    top = SettingsConstants.PAGE_PADDING
                )
            )

            Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_EXTRA_LARGE))

            Text(
                text = stringResource(R.string.playback_cache),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = SettingsConstants.PAGE_PADDING,
                    end = SettingsConstants.PAGE_PADDING,
                    bottom = SettingsConstants.SPACER_HEIGHT_MEDIUM
                )
            )

            var expandedCacheSize by remember { mutableStateOf(false) }
            val cacheSizes = listOf(
                256L * 1024 * 1024 to "256 MB",
                512L * 1024 * 1024 to "512 MB",
                1024L * 1024 * 1024 to "1 GB",
                2048L * 1024 * 1024 to "2 GB",
                5L * 1024 * 1024 * 1024 to stringResource(R.string.unlimited)
            )
            val currentCacheSizeLabel = cacheSizes.find { it.first == uiState.maxCoverCacheSize }?.second 
                ?: "${uiState.maxCoverCacheSize / (1024 * 1024)} MB"

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM)) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsConstants.PAGE_PADDING)) {
                    ExposedDropdownMenuBox(
                        expanded = expandedCacheSize,
                        onExpandedChange = { expandedCacheSize = !expandedCacheSize },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = currentCacheSizeLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.max_cover_cache)) },
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
                                        viewModel.updateMaxCoverCacheSize(size)
                                        expandedCacheSize = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.cover_cache_restart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING + 4.dp, vertical = 6.dp)
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.clear_internal_cache)) },
                supportingContent = { Text(stringResource(R.string.used_space, uiState.internalCacheSize)) },
                trailingContent = {
                    TextButton(
                        onClick = { viewModel.requestClearCache(CacheClearTarget.ALL) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM))

            Text(
                text = stringResource(R.string.persistent_storage),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = SettingsConstants.PAGE_PADDING,
                    end = SettingsConstants.PAGE_PADDING,
                    bottom = SettingsConstants.SPACER_HEIGHT_MEDIUM
                )
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
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.reset),
                                    tint = MaterialTheme.colorScheme.error
                                )
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

            HorizontalDivider(modifier = Modifier.padding(vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM))

            Text(
                text = stringResource(R.string.maintenance_tools),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = SettingsConstants.PAGE_PADDING,
                    end = SettingsConstants.PAGE_PADDING,
                    bottom = SettingsConstants.SPACER_HEIGHT_MEDIUM
                )
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
                        if (uiState.isScanning) {
                            SpinningStorageRefreshIcon()
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
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
                        if (uiState.isInteractiveScanning && uiState.interactiveScanStatus.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${uiState.interactiveScanStatus} (${uiState.interactiveScanProgress}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                leadingContent = {
                    if (uiState.isInteractiveScanning) {
                        SpinningStorageRefreshIcon()
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_sync),
                            contentDescription = null
                        )
                    }
                },
                trailingContent = {
                    if (uiState.isInteractiveScanning) {
                        CircularProgressIndicator(
                            progress = { (uiState.interactiveScanProgress).toFloat() / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                modifier = Modifier
                    .alpha(if (isEffectivelyOnline) 1.0f else 0.38f)
                    .clickable(enabled = !uiState.isInteractiveScanning && isEffectivelyOnline) { viewModel.requestSyncWithServer() }
            )
        }
    }

    uiState.pendingClearCacheTarget?.let { target ->
        de.lwp2070809.speculonic.ui.screens.settings.components.ClearCacheConfirmDialog(
            target = target,
            onConfirm = { viewModel.confirmClearCache() },
            onDismiss = { viewModel.cancelClearCache() }
        )
    }

    if (uiState.showSilentCacheConfirm) {
        de.lwp2070809.speculonic.ui.screens.settings.components.SilentCacheConfirmDialog(
            onConfirm = { viewModel.confirmDisableSilentCache() },
            onDismiss = { viewModel.cancelDisableSilentCache() }
        )
    }

    if (uiState.showMobileSyncConfirm) {
        de.lwp2070809.speculonic.ui.screens.settings.components.MobileSyncConfirmDialog(
            onConfirm = { viewModel.syncWithServer() },
            onDismiss = { viewModel.cancelMobileSync() }
        )
    }

    if (uiState.isInteractiveScanning) {
        de.lwp2070809.speculonic.ui.screens.settings.components.InteractiveScanningDialog(
            progress = uiState.interactiveScanProgress,
            statusText = uiState.interactiveScanStatus
        )
    }

    uiState.storageError?.let { error ->
        val titleRes = if (error.source == StorageErrorSource.SCAN) {
            R.string.scan_local_files
        } else {
            R.string.verify_cache
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissInconsistencyDialog,
            title = { Text(stringResource(titleRes)) },
            text = { Text(error.message) },
            confirmButton = {
                if (error.showConfigureSafAction) {
                    TextButton(onClick = {
                        viewModel.dismissInconsistencyDialog()
                        directoryLauncher.launch(null)
                    }) { Text(stringResource(R.string.configure_saf)) }
                } else {
                    TextButton(onClick = viewModel::dismissInconsistencyDialog) {
                        Text(stringResource(R.string.close))
                    }
                }
            },
            dismissButton = if (error.showConfigureSafAction) {
                {
                    TextButton(onClick = viewModel::dismissInconsistencyDialog) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                null
            }
        )
    }

    if (uiState.showInconsistencyDialog) {
        InconsistencyDialog(
            inconsistentItems = uiState.inconsistentItems,
            isResolving = uiState.isResolving,
            resolutionMessage = uiState.resolutionMessage,
            onDismissRequest = viewModel::dismissInconsistencyDialog,
            onResolve = viewModel::resolveInconsistencies
        )
    }

    if (uiState.safetyGuardConfirmations.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSafetyGuard() },
            title = { Text(stringResource(R.string.safety_guard_title)) },
            text = { Text(uiState.safetyGuardMessage ?: stringResource(R.string.safety_guard_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSafetyGuard() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelSafetyGuard() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
