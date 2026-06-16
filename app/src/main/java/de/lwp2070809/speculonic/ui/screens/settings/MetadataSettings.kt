package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.metadata)
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

    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            MetadataDashboardCard(
                artists = uiState.artistsCount,
                albums = uiState.albumsCount,
                songs = uiState.songsCount,
                playlists = uiState.playlistsCount,
                lastSyncTime = uiState.lastSyncTime,
                modifier = Modifier.padding(start = SettingsConstants.PAGE_PADDING, end = SettingsConstants.PAGE_PADDING, top = SettingsConstants.PAGE_PADDING)
            )

            Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_EXTRA_LARGE))

            Text(
                text = stringResource(R.string.metadata_sync),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = SettingsConstants.PAGE_PADDING, end = SettingsConstants.PAGE_PADDING, bottom = SettingsConstants.SPACER_HEIGHT_MEDIUM)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.background_sync)) },
                supportingContent = { Text(stringResource(R.string.background_sync_description)) },
                trailingContent = {
                    Switch(
                        checked = uiState.backgroundSyncEnabled,
                        onCheckedChange = { viewModel.updateBackgroundSyncEnabled(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.force_full_sync)) },
                supportingContent = { Text(stringResource(R.string.force_full_sync_description)) },
                trailingContent = {
                    IconButton(
                        onClick = { viewModel.requestForceSync() },
                        enabled = !uiState.isSyncing
                    ) {
                        Icon(
                            imageVector = if (uiState.isSyncing) Icons.Default.Refresh else Icons.Default.Sync,
                            contentDescription = stringResource(R.string.force_full_sync),
                            modifier = if (uiState.isSyncing) Modifier.rotate(rotation) else Modifier
                        )
                    }
                }
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_cover_art_option_title)) },
                supportingContent = { Text(stringResource(R.string.sync_cover_art_option_desc)) },
                trailingContent = {
                    Switch(
                        checked = uiState.syncCoverArtOnForce,
                        onCheckedChange = { viewModel.updateSyncCoverArtOnForce(it) }
                    )
                },
                modifier = Modifier.padding(start = SettingsConstants.SUB_ITEM_PADDING)
            )

            if (uiState.isSyncing && uiState.syncProgress != null) {
                Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = SettingsConstants.PAGE_PADDING, end = SettingsConstants.PAGE_PADDING, bottom = SettingsConstants.PAGE_PADDING)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(rotation),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = uiState.syncProgress ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (uiState.showForceSyncConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelForceSync() },
            title = { Text(stringResource(R.string.confirm_force_sync_title)) },
            text = { Text(stringResource(R.string.confirm_force_sync_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmForceSync() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelForceSync() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (uiState.showSafetyGuardConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSafetyGuard() },
            title = { Text("安全警戒线触发") },
            text = { Text(uiState.safetyGuardMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSafetyGuard() }) {
                    Text("继续同步 (覆盖本地)", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelSafetyGuard() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun MetadataDashboardCard(
    artists: Int,
    albums: Int,
    songs: Int,
    playlists: Int,
    lastSyncTime: Long,
    modifier: Modifier = Modifier
) {
    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer
        )
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.metadata_stats),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DashboardItem(
                    count = artists,
                    label = stringResource(R.string.artists_count_label),
                    icon = Icons.Default.Person,
                    modifier = Modifier.weight(1f)
                )
                DashboardItem(
                    count = albums,
                    label = stringResource(R.string.albums_count_label),
                    icon = Icons.Default.Album,
                    modifier = Modifier.weight(1f)
                )
                DashboardItem(
                    count = songs,
                    label = stringResource(R.string.songs_count_label),
                    icon = Icons.Default.MusicNote,
                    modifier = Modifier.weight(1f)
                )
                DashboardItem(
                    count = playlists,
                    label = stringResource(R.string.playlists_count_label),
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))

            val timeStr = remember(lastSyncTime) {
                if (lastSyncTime <= 0L) {
                    "Never"
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTime))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    text = stringResource(R.string.last_sync_success_time) + timeStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun DashboardItem(
    count: Int,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
        Text(
            text = count.toString(),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}
