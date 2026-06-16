package de.lwp2070809.speculonic.ui.screens.details

import de.lwp2070809.speculonic.R

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    topBarState: TopBarState,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val removeSuccessMsg = stringResource(R.string.remove_from_playlist_success)
    val removeErrorMsg = stringResource(R.string.remove_from_playlist_error)
    val downloadController = remember(repository) { DownloadController(context, repository) }
    val activeDownloads by DownloadTracker.activeDownloadIds.collectAsState()
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    val isAnyDownloading = remember(activeDownloads, uiState.songs) {
        uiState.songs.any { activeDownloads.contains(it.id) }
    }

    
    val isAllDownloaded = remember(downloadedIds, uiState.songs) {
        uiState.songs.isNotEmpty() && uiState.songs.all { it.isFullyCached || downloadedIds.contains(it.id) }
    }
    val playlistTitle = stringResource(R.string.playlists)

    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    
    LaunchedEffect(uiState.playlist) {
        topBarState.update(
            title = uiState.playlist?.name ?: "",
            showBack = true,
            onBackClickOverride = onBackClick,
            token = screenToken
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { if (isOnline) viewModel.loadPlaylistDetails(forceRefresh = true, isManualRefresh = true) },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (uiState.isLoading && uiState.playlist == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null && uiState.playlist == null) {
                Text(text = uiState.error!!, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
            } else if (uiState.playlist != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        PlaylistHeader(
                            playlist = uiState.playlist,
                            songCountOverride = uiState.songs.size,
                            onTogglePinned = { viewModel.togglePinned() }
                        )
                    }

                    if (uiState.songs.isNotEmpty()) {
                        item {
                            ActionButtonsRow(
                                onPlayAll = {
                                    val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                    playbackController.play(mediaItems, 0, queueTitle = uiState.playlist?.name)
                                },
                                onShuffle = {
                                    val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                    playbackController.play(mediaItems, if (mediaItems.isNotEmpty()) mediaItems.indices.random() else 0, shuffle = true, queueTitle = uiState.playlist?.name)
                                },
                                onDownloadAll = {
                                    uiState.songs.forEach { song ->
                                        val isDownloaded = song.isFullyCached || downloadedIds.contains(song.id)
                                        if (!isDownloaded) {
                                            downloadController.downloadSong(song)
                                        }
                                    }
                                },
                                isOnline = isOnline,
                                isEffectivelyOnline = isEffectivelyOnline,
                                isDownloading = isAnyDownloading,
                                isDownloadEnabled = !isAllDownloaded
                            )
                        }
                    }

                    itemsIndexed(uiState.songs) { index, song ->
                        var showItemMenu by remember { mutableStateOf(false) }
                        var showDetailDialog by remember { mutableStateOf(false) }
                        val isDownloaded = song.isFullyCached || downloadedIds.contains(song.id)
                        
                        if (showDetailDialog) {
                            de.lwp2070809.speculonic.ui.components.SongDetailDialog(song = song, onDismiss = { showDetailDialog = false })
                        }
                        
                        SongListItem(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            isOnline = isOnline,
                            isEffectivelyOnline = isEffectivelyOnline,
                            onClick = {
                                val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                playbackController.play(mediaItems, index, queueTitle = uiState.playlist?.name)
                            },
                            onStarClick = { star ->
                                scope.launch {
                                    repository.starSong(song.id, star)
                                }
                            },
                            onDownloadClick = {
                                downloadController.downloadSong(song)
                            },
                            onRemoveDownloadClick = {
                                downloadController.removeDownload(song.id)
                            },
                            trailingContentOverride = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    var isStarred by remember(song.id, song.starred) { mutableStateOf(song.starred != null) }
                                    IconButton(
                                        onClick = {
                                            isStarred = !isStarred
                                            scope.launch {
                                                repository.starSong(song.id, isStarred)
                                            }
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
                                        IconButton(onClick = { showItemMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = showItemMenu,
                                            onDismissRequest = { showItemMenu = false }
                                        ) {
                                            if (isDownloaded) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.remove_download)) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                                    onClick = {
                                                        showItemMenu = false
                                                        downloadController.removeDownload(song.id)
                                                    }
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.download)) },
                                                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                                    onClick = {
                                                        showItemMenu = false
                                                        downloadController.downloadSong(song)
                                                    },
                                                    enabled = isEffectivelyOnline
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.song_details)) },
                                                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                                onClick = {
                                                    showDetailDialog = true
                                                    showItemMenu = false
                                                }
                                            )
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.remove_from_playlist)) },
                                                leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
                                                onClick = {
                                                    showItemMenu = false
                                                    scope.launch {
                                                        val success = repository.removeFromPlaylist(uiState.playlist?.id ?: "", index)
                                                        val msg = if (success) removeSuccessMsg else removeErrorMsg
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                enabled = isOnline
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistHeader(
    playlist: Playlist?,
    songCountOverride: Int = 0,
    onTogglePinned: () -> Unit = {}
) {
    val repository = LocalSubsonicRepository.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val model = remember(playlist?.coverArt) {
            repository.buildCoverArtRequest(playlist?.coverArt, context, preferLocal = true)
        }

        AsyncImage(
            model = model,
            contentDescription = playlist?.name,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist?.name ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = playlist?.owner ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            val songCount = if ((playlist?.songCount ?: 0) == 0 && songCountOverride > 0) {
                songCountOverride
            } else {
                playlist?.songCount ?: 0
            }
            Text(
                text = pluralStringResource(R.plurals.songs_count, songCount, songCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        IconButton(onClick = onTogglePinned) {
            if (playlist?.pinned == true) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "Unpin playlist",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = "Pin playlist",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
