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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.screens.settings.components.BindSettingsTopBar

@Composable
fun NetworkSettings(viewModel: GeneralSettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = stringResource(R.string.network)

    BindSettingsTopBar(topBarState = topBarState, title = title)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(top = SettingsConstants.PAGE_PADDING, bottom = SettingsConstants.PAGE_PADDING + LocalMiniPlayerBottomInset.current)
    ) {
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
            headlineContent = { Text(stringResource(R.string.pref_auto_offline_on_metered_title)) },
            supportingContent = { Text(stringResource(R.string.pref_auto_offline_on_metered_summary)) },
            trailingContent = {
                Switch(
                    checked = uiState.autoOfflineOnMetered,
                    onCheckedChange = { viewModel.updateAutoOfflineOnMetered(it) }
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


