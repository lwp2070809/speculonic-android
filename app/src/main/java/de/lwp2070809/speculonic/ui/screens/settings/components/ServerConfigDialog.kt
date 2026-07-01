package de.lwp2070809.speculonic.ui.screens.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import de.lwp2070809.speculonic.ui.screens.settings.SettingsConstants
import de.lwp2070809.speculonic.ui.screens.settings.SettingsViewModel

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
                    placeholder = { Text(stringResource(R.string.server_url_placeholder)) },
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
                                painter = if (result.first) androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.CheckCircle) else androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_error),
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
