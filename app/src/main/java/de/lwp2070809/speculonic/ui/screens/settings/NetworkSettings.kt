package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState

@Composable
fun NetworkSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.network)

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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical = 16.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.mobile_play_allowed)) },
            trailingContent = {
                Switch(
                    checked = uiState.mobilePlayAllowed,
                    onCheckedChange = { viewModel.updateMobilePlayAllowed(it) }
                )
            }
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.pref_show_offline_toast_title)) },
            supportingContent = { Text(stringResource(R.string.pref_show_offline_toast_summary)) },
            trailingContent = {
                Switch(
                    checked = uiState.showOfflineToast,
                    onCheckedChange = { viewModel.updateShowOfflineToast(it) }
                )
            }
        )
    }
}


