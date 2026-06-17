package de.lwp2070809.speculonic.ui.screens.details

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
    topBarState: TopBarState,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
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
    val downloadController = remember(repository) { DownloadController(context, repository) }
    val activeDownloads by DownloadTracker.activeDownloadIds.collectAsState()
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    
    val screenToken = remember { java.util.UUID.randomUUID().toString() }
    
    
    LaunchedEffect(uiState.album, isEffectivelyOnline) {
        topBarState.update(
            title = uiState.album?.name ?: "",
            actions = {
                IconButton(
                    onClick = { viewModel.toggleStar() },
                    enabled = isOnline && uiState.album != null
                ) {
                    val isStarred = uiState.album?.starred != null
                    Icon(
                        if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Star",
                        tint = if (isStarred) MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
            },
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

    
    val isAnyDownloading = remember(activeDownloads, uiState.songs) {
        uiState.songs.any { activeDownloads.contains(it.id) }
    }

    
    val isAllDownloaded = remember(downloadedIds, uiState.songs) {
        uiState.songs.isNotEmpty() && uiState.songs.all { it.isFullyCached || downloadedIds.contains(it.id) }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { if (isOnline) viewModel.loadAlbumDetails(forceRefresh = true, isManualRefresh = true) },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (uiState.isLoading && uiState.album == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null && uiState.album == null) {
                Text(text = uiState.error!!, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
            } else if (uiState.album != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    item {
                        AlbumHeader(album = uiState.album)
                    }

                    if (uiState.songs.isNotEmpty()) {
                        item {
                            ActionButtonsRow(
                                onPlayAll = {
                                    val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                    playbackController.play(mediaItems, 0, queueTitle = uiState.album?.name)
                                },
                                onShuffle = {
                                    val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                    playbackController.play(mediaItems, if (mediaItems.isNotEmpty()) mediaItems.indices.random() else 0, shuffle = true, queueTitle = uiState.album?.name)
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
                                isStreamingAllowed = isStreamingAllowed,
                                isDownloading = isAnyDownloading,
                                isDownloadEnabled = !isAllDownloaded
                            )
                        }
                    }

                    itemsIndexed(uiState.songs, key = { _, song -> song.id }) { index, song ->
                        SongListItem(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            isOnline = isOnline,
                            isEffectivelyOnline = isEffectivelyOnline,
                            isStreamingAllowed = isStreamingAllowed,
                            onClick = {
                                val mediaItems = uiState.songs.map { it.toMediaItem(repository) }
                                playbackController.play(mediaItems, index, queueTitle = uiState.album?.name)
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
                            }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun AlbumHeader(album: Album?) {
    val repository = LocalSubsonicRepository.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val model = remember(album?.coverArt) {
            repository.buildCoverArtRequest(album?.coverArt, context, preferLocal = true)
        }

        AsyncImage(
            model = model,
            contentDescription = album?.name,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album?.name ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album?.artist ?: stringResource(R.string.unknown_artist),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${album?.songCount ?: 0} ${stringResource(R.string.songs)} • ${FormatUtils.formatDuration(album?.duration ?: 0)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

