package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState


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

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))
        
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

    }
}
