package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
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
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.replay_gain)) },
            supportingContent = { Text(stringResource(R.string.replay_gain_description)) },
            trailingContent = {
                Switch(
                    checked = uiState.replayGainEnabled,
                    onCheckedChange = { viewModel.updateReplayGainEnabled(it) }
                )
            }
        )

    }
}
