package de.lwp2070809.speculonic.ui.screens.details

import de.lwp2070809.speculonic.R

import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lwp2070809.speculonic.ui.components.showOfflineRefreshToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.MediaDetailHeader
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.TopBarState
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
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onBackClick: () -> Unit
) {
    val mediaItemConverter = LocalMediaItemConverter.current
    val downloadController = LocalDownloadController.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsStateWithLifecycle()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayedSongs = uiState.songs
    val context = LocalContext.current
    val removeSuccessMsg = stringResource(R.string.remove_from_playlist_success)
    val removeErrorMsg = stringResource(R.string.remove_from_playlist_error)
    val playlistNotFoundMsg = stringResource(R.string.playlist_not_found)
    val activeDownloads = uiState.activeDownloads
    val downloadedIds = uiState.downloadedIds
    val isAnyDownloading = remember(activeDownloads, uiState.songs) {
        uiState.songs.any { activeDownloads.contains(it.id) }
    }

    
    val isAllDownloaded = remember(downloadedIds, uiState.songs) {
        uiState.songs.isNotEmpty() && uiState.songs.all { it.isFullyCached }
    }

    val transcodeIncompatible = uiState.transcodeIncompatible

    val playableIndices = remember(uiState.songs, transcodeIncompatible) {
        uiState.songs.indices.filter { index ->
            de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(uiState.songs[index], transcodeIncompatible)
        }
    }
    val playableSongs = remember(uiState.songs, playableIndices) {
        playableIndices.map(uiState.songs::get)
    }

    val isPlayActionsEnabled = remember(playableSongs, downloadedIds, isStreamingAllowed) {
        playableSongs.isNotEmpty() && (isStreamingAllowed || playableSongs.any { it.isFullyCached })
    }

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
            Toast.makeText(context, playlistNotFoundMsg, Toast.LENGTH_SHORT).show()
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
        onRefresh = {
            if (isEffectivelyOnline) viewModel.loadPlaylistDetails(forceRefresh = true, isManualRefresh = true)
            else context.showOfflineRefreshToast()
        },
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
                    item(key = "playlist_header", contentType = "header") {
                        PlaylistHeader(
                            playlist = uiState.playlist,
                            songCountOverride = uiState.songs.size,
                            onTogglePinned = { viewModel.togglePinned() }
                        )
                    }

                    if (uiState.songs.isNotEmpty()) {
                        item(key = "action_row", contentType = "action_row") {
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
                                        playbackController.play(
                                            mediaItems,
                                            mediaItems.indices.random(),
                                            shuffle = true,
                                            queueTitle = uiState.playlist?.name
                                        )
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
                                isStreamingAllowed = isStreamingAllowed,
                                isDownloading = isAnyDownloading,
                                isDownloadEnabled = !isAllDownloaded,
                                isPlayAllEnabled = isPlayActionsEnabled,
                                isShuffleEnabled = isPlayActionsEnabled
                            )
                        }
                    }

                    if (uiState.songs.isEmpty()) {
                        item(key = "empty_content", contentType = "empty_content") {
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

                    itemsIndexed(
                        items = displayedSongs,
                        key = { index, song -> "${song.id}_$index" },
                        contentType = { _, _ -> "song_item" }
                    ) { index, song ->
                        SongListItem(
                            song = song,
                            isCurrent = song.id == currentSongId,
                            isEffectivelyOnline = isEffectivelyOnline,
                            isStreamingAllowed = isStreamingAllowed,
                            transcodeIncompatible = transcodeIncompatible,
                            onClick = {
                                val playIndex = playableIndices.binarySearch(index)
                                if (playIndex >= 0) {
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    playbackController.play(mediaItems, playIndex, queueTitle = uiState.playlist?.name)
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
                                viewModel.removeFromPlaylist(song.id, index, displayedSongs) { success ->
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
    val songCount = if ((playlist?.songCount ?: 0) == 0 && songCountOverride > 0) {
        songCountOverride
    } else {
        playlist?.songCount ?: 0
    }
    MediaDetailHeader(
        coverArtId = playlist?.coverArt,
        title = playlist?.name ?: "",
        subtitle = playlist?.owner ?: "",
        metaText = pluralStringResource(R.plurals.songs_count, songCount, songCount),
        trailingContent = {
            IconButton(onClick = onTogglePinned) {
                if (playlist?.pinned == true) {
                    Icon(
                        androidx.compose.ui.res.painterResource(id = R.drawable.ic_symbol_push_pin_filled),
                        contentDescription = stringResource(R.string.content_description_unpin_playlist),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        androidx.compose.ui.res.painterResource(id = R.drawable.ic_symbol_push_pin),
                        contentDescription = stringResource(R.string.content_description_pin_playlist),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
