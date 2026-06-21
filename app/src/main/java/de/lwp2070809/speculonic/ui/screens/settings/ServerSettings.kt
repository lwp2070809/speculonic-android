package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState

@Composable
fun ServerSettings(
    viewModel: SettingsViewModel,
    topBarState: TopBarState,
    isEffectivelyOnline: Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val title = stringResource(R.string.server)

    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = SettingsConstants.PAGE_PADDING)
            .verticalScroll(rememberScrollState())
    ) {
        if (uiState.serverUrl.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = SettingsConstants.PAGE_PADDING)
                    .alpha(if (isEffectivelyOnline) 1.0f else 0.38f),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { showEditDialog = true },
                    enabled = isEffectivelyOnline
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                    Text(stringResource(R.string.setup_server))
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsConstants.PAGE_PADDING),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = uiState.serverUrl,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = uiState.username,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.alpha(if (isEffectivelyOnline) 1.0f else 0.38f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { showEditDialog = true },
                            enabled = isEffectivelyOnline
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = isEffectivelyOnline
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.server_dashboard_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING, vertical = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsConstants.PAGE_PADDING)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val caps = uiState.serverCapabilities
                    val type = caps?.type ?: stringResource(R.string.unknown_val)
                    val serverVer = caps?.serverVersion ?: stringResource(R.string.unknown_val)
                    val apiVer = caps?.subsonicApiVersion ?: stringResource(R.string.unknown_val)

                    DashboardItem(
                        icon = Icons.Default.Storage,
                        label = stringResource(R.string.server_type),
                        value = type
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    DashboardItem(
                        icon = Icons.Default.Info,
                        label = stringResource(R.string.server_version),
                        value = serverVer
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    DashboardItem(
                        icon = Icons.Default.Code,
                        label = stringResource(R.string.subsonic_api_version),
                        value = apiVer
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = stringResource(R.string.opensubsonic_support),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OpenSubsonicBadge(supported = caps?.isOpenSubsonic == true)
                    }

                }
            }

            if (uiState.serverUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.metadata_sync),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING, vertical = 8.dp)
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
                    trailingContent = {
                        IconButton(
                            onClick = { viewModel.requestForceSync() },
                            enabled = !uiState.isSyncing && isEffectivelyOnline
                        ) {
                            Icon(
                                imageVector = if (uiState.isSyncing) Icons.Default.Refresh else Icons.Default.Sync,
                                contentDescription = stringResource(R.string.force_full_sync),
                                modifier = if (uiState.isSyncing) Modifier.rotate(rotation) else Modifier
                            )
                        }
                    },
                    modifier = Modifier.alpha(if (isEffectivelyOnline) 1.0f else 0.38f)
                )

                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_cover_art_option_title)) },
                    supportingContent = { Text(stringResource(R.string.sync_cover_art_option_desc)) },
                    trailingContent = {
                        Switch(
                            checked = uiState.syncCoverArtOnForce,
                            onCheckedChange = { viewModel.updateSyncCoverArtOnForce(it) }
                        )
                    },
                    modifier = Modifier.padding(start = SettingsConstants.SUB_ITEM_PADDING)
                )

                if (uiState.isSyncing && uiState.syncProgress != null) {
                    Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = SettingsConstants.PAGE_PADDING, end = SettingsConstants.PAGE_PADDING, bottom = SettingsConstants.PAGE_PADDING)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(rotation),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = uiState.syncProgress ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        ServerConfigDialog(
            initialUrl = uiState.serverUrl,
            initialUser = uiState.username,
            initialPass = uiState.password,
            viewModel = viewModel,
            onDismiss = { showEditDialog = false },
            onSave = { url, user, pass, syncCoverArt ->
                viewModel.updateServerUrl(url)
                viewModel.updateUsername(user)
                viewModel.updatePassword(pass)
                if (viewModel.saveSettings(syncCoverArt)) {
                    showEditDialog = false
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_server)) },
            text = { Column { Text(stringResource(R.string.confirm_delete_server)); Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_MEDIUM)); Text(text = stringResource(R.string.delete_server_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteServerSettings()
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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

    if (uiState.showSafetyGuardConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSafetyGuard() },
            title = { Text("安全警戒线触发") },
            text = { Text(uiState.safetyGuardMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSafetyGuard() }) {
                    Text("继续同步 (覆盖本地)", color = MaterialTheme.colorScheme.error)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigDialog(
    initialUrl: String,
    initialUser: String,
    initialPass: String,
    viewModel: SettingsViewModel,
    showCancelButton: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    val uiState by viewModel.uiState.collectAsState()
    var trustAllCerts by remember { mutableStateOf(uiState.trustAllCertificates) }
    var showTrustAllWarning by remember { mutableStateOf(false) }
    var syncCoverArt by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_configuration)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_metadata_data_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))
                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        viewModel.updateServerUrl(it) 
                    },
                    label = { Text(stringResource(R.string.server_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://your-server.com") },
                    isError = uiState.urlError != null,
                    supportingText = if (uiState.urlError != null) {
                        { Text(uiState.urlError!!) }
                    } else null
                )
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))
                
                
                HorizontalDivider()
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_cover_art_option_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.sync_cover_art_option_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                    Switch(
                        checked = syncCoverArt,
                        onCheckedChange = { syncCoverArt = it }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.trust_all_certificates),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.trust_all_certificates_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = trustAllCerts,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                showTrustAllWarning = true
                            } else {
                                trustAllCerts = false
                                viewModel.updateTrustAllCertificates(false)
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { viewModel.testConnection(url, user, pass) },
                        enabled = !uiState.isTestingConnection && url.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text(stringResource(R.string.test_connection))
                        }
                    }
                    
                    AnimatedVisibility(visible = uiState.testConnectionResult != null) {
                        val result = uiState.testConnectionResult
                        if (result != null) {
                            Icon(
                                imageVector = if (result.first) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (result.first) Color.Green else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                
                uiState.testConnectionResult?.let { result ->
                    if (!result.first && result.second != null) {
                        Text(
                            text = result.second!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, user, pass, syncCoverArt) },
                enabled = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
            ) {
                Text(stringResource(R.string.save_configuration))
            }
        },
        dismissButton = if (showCancelButton) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else null
    )

    if (showTrustAllWarning) {
        AlertDialog(
            onDismissRequest = { showTrustAllWarning = false },
            title = { Text(stringResource(R.string.warning_title)) },
            text = { Text(stringResource(R.string.trust_all_certs_warning_message), color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = {
                    trustAllCerts = true
                    viewModel.updateTrustAllCertificates(true)
                    showTrustAllWarning = false
                }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrustAllWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun DashboardItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun OpenSubsonicBadge(supported: Boolean) {
    val containerColor = if (supported) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (supported) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val text = if (supported) {
        stringResource(R.string.opensubsonic_supported)
    } else {
        stringResource(R.string.opensubsonic_not_supported)
    }

    Box(
        modifier = Modifier
            .background(containerColor, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor
        )
    }
}




