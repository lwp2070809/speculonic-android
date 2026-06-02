package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.PlaylistAddResult
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.FormatUtils
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun SongListItem(
    song: Song,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onClick: () -> Unit,
    onStarClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: () -> Unit = {},
    onRemoveDownloadClick: () -> Unit = {},
    trailingContentOverride: @Composable (() -> Unit)? = null
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val currentSongId by remember(playbackController) {
        playbackController.playbackState
            .map { it.currentSongId }
            .distinctUntilChanged()
    }.collectAsState(initial = "")
    val isCurrent = currentSongId == song.id

    var isStarred by remember(song.id, song.starred) { mutableStateOf(song.starred != null) }
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    val isDownloaded = song.isFullyCached || downloadedIds.contains(song.id)
    
    
    val isEnabled = isEffectivelyOnline || isDownloaded
    val alpha = if (isEnabled) 1.0f else 0.38f

    var showMenu by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val successMsg = stringResource(R.string.add_to_playlist_success)
    val existsMsg = stringResource(R.string.song_already_in_playlist)
    val errorMsg = stringResource(R.string.add_to_playlist_error)

    if (showDetailDialog) {
        SongDetailDialog(song = song, onDismiss = { showDetailDialog = false })
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            song = song,
            repository = repository,
            onDismiss = { showAddToPlaylistDialog = false },
            onResult = { result ->
                val message = when (result) {
                    PlaylistAddResult.SUCCESS -> successMsg
                    PlaylistAddResult.ALREADY_EXISTS -> existsMsg
                    PlaylistAddResult.ERROR -> errorMsg
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    ListItem(
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
                if (isDownloaded) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        supportingContent = { 
            Text(
                text = "${song.artist ?: ""} • ${FormatUtils.formatDuration(song.duration ?: 0)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            ) 
        },
        leadingContent = {
            val model = remember(song.coverArt) {
                repository.buildCoverArtRequest(song.coverArt, context, preferLocal = true)
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (isCurrent) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        }
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            if (trailingContentOverride != null) {
                trailingContentOverride()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { 
                            isStarred = !isStarred
                            onStarClick(isStarred)
                        },
                        enabled = isOnline
                    ) {
                        Icon(
                            imageVector = if (isStarred) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Star",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (isDownloaded) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_download)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = { 
                                        onRemoveDownloadClick()
                                        showMenu = false
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download)) },
                                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                    onClick = { 
                                        onDownloadClick()
                                        showMenu = false
                                    },
                                    enabled = isEffectivelyOnline
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.song_details)) },
                                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                onClick = { 
                                    showDetailDialog = true
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_to_playlist)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                                onClick = { 
                                    showAddToPlaylistDialog = true
                                    showMenu = false
                                },
                                enabled = isOnline
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
            .alpha(alpha)
            .clickable(enabled = isEnabled) { onClick() }
    )
}
