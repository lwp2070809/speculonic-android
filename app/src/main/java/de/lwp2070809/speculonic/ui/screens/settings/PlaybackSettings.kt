package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.screens.settings.components.BindSettingsTopBar
import de.lwp2070809.speculonic.util.MediaFormatUtils

@Composable
fun PlaybackSettings(viewModel: PlaybackBluetoothViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.play)
    var showFormatDialog by rememberSaveable { mutableStateOf(false) }

    BindSettingsTopBar(topBarState = topBarState, title = title)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(top = SettingsConstants.PAGE_PADDING, bottom = SettingsConstants.PAGE_PADDING + LocalMiniPlayerBottomInset.current)
    ) {

        Text(
            text = stringResource(R.string.transient_focus_change),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING, vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM)
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.duck_on_transient)) },
            supportingContent = { Text(stringResource(R.string.duck_on_transient_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.duckOnTransientFocusLoss,
                    onCheckedChange = { viewModel.updateDuckOnTransientFocusLoss(it) }
                )
            }
        )

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))

        Text(
            text = stringResource(R.string.permanent_focus_change),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING, vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM)
        )
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.pause_on_loss)) },
            supportingContent = { Text(stringResource(R.string.pause_on_loss_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.pauseOnAudioFocusLoss,
                    onCheckedChange = { viewModel.updatePauseOnAudioFocusLoss(it) }
                )
            }
        )

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))

        Text(
            text = stringResource(R.string.playback_other),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SettingsConstants.PAGE_PADDING, vertical = SettingsConstants.SPACER_HEIGHT_MEDIUM)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.pause_on_volume_zero)) },
            supportingContent = { Text(stringResource(R.string.pause_on_volume_zero_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.pauseOnVolumeZero,
                    onCheckedChange = { viewModel.updatePauseOnVolumeZero(it) }
                )
            }
        )

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

        if (uiState.transcodeIncompatibleFormats) {
            val currentFormatLabel = when (uiState.targetTranscodeFormat.lowercase()) {
                "mp3" -> stringResource(R.string.format_mp3)
                "flac" -> stringResource(R.string.format_flac)
                "opus" -> stringResource(R.string.format_opus)
                else -> uiState.targetTranscodeFormat.uppercase()
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.target_transcode_format)) },
                supportingContent = { Text(currentFormatLabel) },
                modifier = Modifier.clickable { showFormatDialog = true }
            )

            if (showFormatDialog) {
                AlertDialog(
                    onDismissRequest = { showFormatDialog = false },
                    title = { Text(stringResource(R.string.target_transcode_format)) },
                    text = {
                        Column {
                            MediaFormatUtils.SUPPORTED_TRANSCODE_FORMATS.forEach { format ->
                                val label = when (format) {
                                    "mp3" -> stringResource(R.string.format_mp3)
                                    "flac" -> stringResource(R.string.format_flac)
                                    "opus" -> stringResource(R.string.format_opus)
                                    else -> format.uppercase()
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.updateTargetTranscodeFormat(format)
                                            showFormatDialog = false
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = uiState.targetTranscodeFormat.equals(format, ignoreCase = true),
                                        onClick = {
                                            viewModel.updateTargetTranscodeFormat(format)
                                            showFormatDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showFormatDialog = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                )
            }
        }

        ListItem(
            headlineContent = { Text(stringResource(R.string.transcode_incompatible_formats)) },
            supportingContent = { Text(stringResource(R.string.transcode_incompatible_formats_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.transcodeIncompatibleFormats,
                    onCheckedChange = { enabled ->
                        if (!enabled) showFormatDialog = false
                        viewModel.updateTranscodeIncompatibleFormats(enabled)
                    }
                )
            }
        )
    }
}
