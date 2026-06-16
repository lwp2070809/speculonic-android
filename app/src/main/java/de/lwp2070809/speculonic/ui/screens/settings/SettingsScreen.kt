package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.navigation.AppRoute

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel, 
    topBarState: TopBarState,
    onNavigate: (AppRoute) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsRoot(onNavigate = onNavigate)
    }
}

@Composable
fun SettingsRoot(onNavigate: (AppRoute) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryItem(
            title = stringResource(R.string.server),
            icon = Icons.Default.Dns,
            onClick = { onNavigate(AppRoute.SettingsServer) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.play),
            icon = Icons.Default.PlayArrow,
            onClick = { onNavigate(AppRoute.SettingsPlayback) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.bluetooth_audio),
            icon = Icons.Default.Bluetooth,
            onClick = { onNavigate(AppRoute.SettingsBluetooth) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.appearance),
            icon = Icons.Default.Palette,
            onClick = { onNavigate(AppRoute.SettingsAppearance) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.metadata),
            icon = Icons.Default.Sync,
            onClick = { onNavigate(AppRoute.MetadataSettings) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.storage_cache_settings),
            icon = Icons.Default.Storage,
            onClick = { onNavigate(AppRoute.StorageCacheSettings) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.network),
            icon = Icons.Default.NetworkCheck,
            onClick = { onNavigate(AppRoute.SettingsNetwork) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.advanced),
            icon = Icons.Default.Terminal,
            onClick = { onNavigate(AppRoute.SettingsAdvanced) }
        )
        SettingsCategoryItem(
            title = stringResource(R.string.about),
            icon = Icons.Default.Info,
            onClick = { onNavigate(AppRoute.SettingsAbout) }
        )
    }
}

@Composable
fun SettingsCategoryItem(title: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

