package de.lwp2070809.speculonic.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState

@Composable
fun BluetoothSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.bluetooth_audio)
    var showBluetoothNamesDialog by remember { mutableStateOf(false) }

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
    
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onBluetoothPermissionResult(isGranted)
    }

    LaunchedEffect(uiState.showBluetoothPermissionRequest) {
        if (uiState.showBluetoothPermissionRequest) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                viewModel.onBluetoothPermissionResult(true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.bluetooth_car_audio),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.identify_car_bluetooth)) },
            supportingContent = { Text(stringResource(R.string.identify_car_bluetooth_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.carBluetoothEnabled,
                    onCheckedChange = { viewModel.updateCarBluetoothEnabled(it) }
                )
            }
        )
        ListItem(
            modifier = Modifier
                .clickable(enabled = uiState.carBluetoothEnabled) { showBluetoothNamesDialog = true }
                .alpha(if (uiState.carBluetoothEnabled) 1f else 0.38f),
            headlineContent = { Text(stringResource(R.string.bluetooth_car_device_names)) },
            supportingContent = { Text(stringResource(R.string.bluetooth_car_device_names_description)) }
        )
        
        ListItem(
            modifier = Modifier.alpha(if (uiState.carBluetoothEnabled) 1f else 0.38f),
            headlineContent = { Text(stringResource(R.string.sync_playback_state)) },
            supportingContent = { Text(stringResource(R.string.sync_playback_state_description)) },
            trailingContent = {
                Switch(
                    enabled = uiState.carBluetoothEnabled,
                    checked = uiState.syncPlaybackState,
                    onCheckedChange = { viewModel.updateSyncPlaybackState(it) }
                )
            }
        )

        ListItem(
            modifier = Modifier.alpha(if (uiState.carBluetoothEnabled) 1f else 0.38f),
            headlineContent = { Text(stringResource(R.string.bluetooth_lyrics)) },
            supportingContent = { Text(stringResource(R.string.bluetooth_lyrics_description)) },
            trailingContent = {
                Switch(
                    enabled = uiState.carBluetoothEnabled,
                    checked = uiState.bluetoothLyricsEnabled,
                    onCheckedChange = { viewModel.updateBluetoothLyricsEnabled(it) }
                )
            }
        )
        
        val showProgressBarEnabled = uiState.carBluetoothEnabled && uiState.bluetoothLyricsEnabled
        ListItem(
            modifier = Modifier
                .padding(start = 16.dp)
                .alpha(if (showProgressBarEnabled) 1f else 0.38f),
            headlineContent = { Text(stringResource(R.string.bluetooth_lyrics_hide_progress_bar)) },
            supportingContent = { Text(stringResource(R.string.bluetooth_lyrics_hide_progress_bar_description)) },
            trailingContent = {
                Switch(
                    enabled = showProgressBarEnabled,
                    checked = uiState.bluetoothLyricsHideProgressBar,
                    onCheckedChange = { viewModel.updateBluetoothLyricsHideProgressBar(it) }
                )
            }
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    if (showBluetoothNamesDialog) {
        BluetoothNamesDialog(
            names = uiState.bluetoothCarDeviceNames,
            onDismiss = { showBluetoothNamesDialog = false },
            onAdd = { viewModel.addBluetoothCarDeviceName(it) },
            onRemove = { viewModel.removeBluetoothCarDeviceName(it) }
        )
    }
}

@Composable
fun BluetoothNamesDialog(
    names: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bluetooth_car_device_names)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.bluetooth_car_device_names_description),
                    style = MaterialTheme.typography.bodySmall
                )
                
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.add_device_name)) },
                    placeholder = { Text(stringResource(R.string.device_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (newName.isNotBlank()) {
                                onAdd(newName)
                                newName = ""
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    },
                    singleLine = true
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    names.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(name) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
