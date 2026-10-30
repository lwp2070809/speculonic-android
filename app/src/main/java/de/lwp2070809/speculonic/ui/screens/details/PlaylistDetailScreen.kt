package de.lwp2070809.speculonic.ui.screens.details

import de.lwp2070809.speculonic.R

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.composition.LocalMediaItemConverter
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    viewModel: PlaylistDetailViewModel,
    topBarState: TopBarState,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val mediaItemConverter = LocalMediaItemConverter.current
    val downloadController = LocalDownloadController.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val removeSuccessMsg = stringResource(R.string.remove_from_playlist_success)
    val removeErrorMsg = stringResource(R.string.remove_from_playlist_error)
    val activeDownloads by DownloadTracker.activeDownloadIds.collectAsState()
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    val isAnyDownloading = remember(activeDownloads, uiState.songs) {
        uiState.songs.any { activeDownloads.contains(it.id) }
    }

    
    val isAllDownloaded = remember(downloadedIds, uiState.songs) {
        uiState.songs.isNotEmpty() && uiState.songs.all { it.isFullyCached }
    }

    val preferencesManager = remember { de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context) }
    val transcodeIncompatible by preferencesManager.transcodeIncompatibleFormats.collectAsState(initial = false)

    val playableSongs = remember(uiState.songs, transcodeIncompatible) {
        uiState.songs.filter { de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible) }
    }

    val isPlayActionsEnabled = remember(playableSongs, downloadedIds, isStreamingAllowed) {
        playableSongs.isNotEmpty() && (isStreamingAllowed || playableSongs.any { it.isFullyCached })
    }
    val playlistTitle = stringResource(R.string.playlists)

    val screenToken = remember { java.util.UUID.randomUUID().toString() }
    var isNavigatingBack by remember { mutableStateOf(false) }
    val safeBackClick: () -> Unit = remember(onBackClick) {
        {
            if (!isNavigatingBack) {
                isNavigatingBack = true
                onBackClick()
            }
        }
    }

    LaunchedEffect(uiState.playlist) {
        topBarState.update(
            title = uiState.playlist?.name ?: "",
            showBack = true,
            onBackClickOverride = safeBackClick,
            token = screenToken
        )
    }

    LaunchedEffect(uiState.isDeleted, uiState.shouldAutoExit) {
        if (uiState.isDeleted && uiState.shouldAutoExit) {
            Toast.makeText(context, context.getString(R.string.playlist_not_found), Toast.LENGTH_SHORT).show()
            safeBackClick()
        }
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.playlist != null && !uiState.isDeleted) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
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
            } else if (uiState.isDeleted) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.playlist_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = safeBackClick) {
                        Text(text = stringResource(R.string.back_to_playlists))
                    }
                }
            } else if (uiState.error != null && uiState.playlist == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = uiState.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = safeBackClick) {
                        Text(text = stringResource(R.string.back_to_playlists))
                    }
                }
            } else if (uiState.playlist != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(bottom = 16.dp + LocalMiniPlayerBottomInset.current)
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
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(mediaItems, 0, shuffle = false, queueTitle = uiState.playlist?.name)
                                    }
                                },
                                onShuffle = {
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(mediaItems, mediaItems.indices.random(), shuffle = true, queueTitle = uiState.playlist?.name)
                                    }
                                },
                                onDownloadAll = {
                                    uiState.songs.forEach { song ->
                                        val isDownloaded = song.isFullyCached
                                        if (!isDownloaded) {
                                            downloadController.downloadSong(song)
                                        }
                                    }
                                },
                                isOnline = isOnline,
                                isEffectivelyOnline = isEffectivelyOnline,
                                isStreamingAllowed = isStreamingAllowed,
                                isDownloading = isAnyDownloading,
                                isDownloadEnabled = !isAllDownloaded,
                                isPlayAllEnabled = isPlayActionsEnabled,
                                isShuffleEnabled = isPlayActionsEnabled
                            )
                        }
                    }

                    if (uiState.songs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.no_content_available),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    itemsIndexed(uiState.songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                        SongListItem(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            isOnline = isOnline,
                            isEffectivelyOnline = isEffectivelyOnline,
                            isStreamingAllowed = isStreamingAllowed,
                            transcodeIncompatible = transcodeIncompatible,
                            onClick = {
                                val playIndex = playableSongs.indexOfFirst { it.id == song.id }
                                if (playIndex != -1) {
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(mediaItems, playIndex, queueTitle = uiState.playlist?.name)
                                    }
                                }
                            },
                            onStarClick = { star ->
                                viewModel.toggleStarSong(song.id, star)
                            },
                            onDownloadClick = {
                                downloadController.downloadSong(song)
                            },
                            onRemoveDownloadClick = {
                                downloadController.removeDownload(song.id)
                            },
                            onRemoveFromPlaylistClick = {
                                viewModel.removeFromPlaylist(song.id, index) { success ->
                                    val msg = if (success) removeSuccessMsg else removeErrorMsg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            isRemoveFromPlaylistEnabled = isEffectivelyOnline && !uiState.isModifying
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
    val coverRequester = LocalCoverArtRequester.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val model = remember(playlist?.coverArt) {
            coverRequester.buildCoverArtRequest(playlist?.coverArt, context, preferLocal = true)
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
                    androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_push_pin_filled),
                    contentDescription = stringResource(R.string.content_description_unpin_playlist),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_push_pin),
                    contentDescription = stringResource(R.string.content_description_pin_playlist),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
