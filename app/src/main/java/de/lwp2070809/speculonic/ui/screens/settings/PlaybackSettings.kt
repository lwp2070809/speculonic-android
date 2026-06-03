package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.play)

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
    
    var expandedSpeed by remember { mutableStateOf(false) }
    var expandedBuffer by remember { mutableStateOf(false) }
    
    val speedOptions = listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
    val bufferOptions = listOf(
        1 to stringResource(R.string.buffer_strategy_data_saver),
        0 to stringResource(R.string.buffer_strategy_balanced),
        2 to stringResource(R.string.buffer_strategy_anti_stutter)
    )

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {

        ListItem(
            headlineContent = { Text(stringResource(R.string.skip_silence)) },
            supportingContent = { Text(stringResource(R.string.skip_silence_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.skipSilenceEnabled,
                    onCheckedChange = { viewModel.updateSkipSilenceEnabled(it) }
                )
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = expandedSpeed,
            onExpandedChange = { expandedSpeed = !expandedSpeed },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = "${uiState.playbackSpeed}x",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.playback_speed)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSpeed) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedSpeed,
                onDismissRequest = { expandedSpeed = false }
            ) {
                speedOptions.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${speed}x") },
                        onClick = {
                            viewModel.updatePlaybackSpeed(speed)
                            expandedSpeed = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = expandedBuffer,
            onExpandedChange = { expandedBuffer = !expandedBuffer },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val currentBufferText = bufferOptions.find { it.first == uiState.bufferStrategy }?.second ?: ""
            OutlinedTextField(
                value = currentBufferText,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.buffer_strategy)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBuffer) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedBuffer,
                onDismissRequest = { expandedBuffer = false }
            ) {
                bufferOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second) },
                        onClick = {
                            viewModel.updateBufferStrategy(option.first)
                            expandedBuffer = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

    }
}
