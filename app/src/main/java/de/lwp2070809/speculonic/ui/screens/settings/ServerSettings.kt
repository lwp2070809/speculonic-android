package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState

@Composable
fun ServerSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val title = stringResource(R.string.server)

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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        if (uiState.serverUrl.isBlank()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { showEditDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_server))
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = uiState.serverUrl, style = MaterialTheme.typography.titleMedium)
                    Text(text = uiState.username, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
            text = { Column { Text(stringResource(R.string.confirm_delete_server)); Spacer(modifier = Modifier.height(8.dp)); Text(text = stringResource(R.string.delete_server_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } },
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
                Spacer(modifier = Modifier.height(16.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
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
                    Spacer(modifier = Modifier.width(8.dp))
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

