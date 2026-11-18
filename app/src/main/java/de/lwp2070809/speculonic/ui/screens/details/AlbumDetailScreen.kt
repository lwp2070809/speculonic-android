package de.lwp2070809.speculonic.ui.screens.details

import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lwp2070809.speculonic.ui.components.showOfflineRefreshToast
import de.lwp2070809.speculonic.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import de.lwp2070809.speculonic.util.MediaFormatUtils
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.MediaDetailHeader
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.rememberSongListItemStrings
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.composition.LocalMediaItemConverter
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    viewModel: AlbumDetailViewModel,
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
    val songStrings = rememberSongListItemStrings()
    val context = LocalContext.current
    val activeDownloads = uiState.activeDownloads
    val downloadedIds = uiState.downloadedIds
    val transcodeIncompatible = uiState.transcodeIncompatible
    
    val screenToken = remember { java.util.UUID.randomUUID().toString() }
    
    
    LaunchedEffect(uiState.album, isEffectivelyOnline) {
        topBarState.update(
            title = uiState.album?.name ?: "",
            actions = {
                IconButton(
                    onClick = { viewModel.toggleStar() },
                    enabled = isEffectivelyOnline && uiState.album != null,
                    modifier = Modifier.alpha(if (isEffectivelyOnline && uiState.album != null) 1.0f else 0.38f)
                ) {
                    val isStarred = uiState.album?.starred != null
                    Icon(
                        painter = if (isStarred) {
                            rememberVectorPainter(Icons.Default.Star)
                        } else {
                            painterResource(id = R.drawable.ic_symbol_star_border)
                        },
                        contentDescription = stringResource(R.string.cd_star),
                        tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            showBack = true,
            onBackClickOverride = onBackClick,
            token = screenToken
        )
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.album != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
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
        uiState.songs.isNotEmpty() && uiState.songs.all { it.isFullyCached }
    }

    val playableSongs = remember(uiState.songs, transcodeIncompatible) {
        uiState.songs.filter { de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible) }
    }

    val discGroups = remember(uiState.songs) {
        uiState.songs.groupBy { it.discNumber ?: 1 }.toSortedMap()
    }
    val isMultiDisc = discGroups.size > 1

    val isPlayActionsEnabled = remember(playableSongs, downloadedIds, isStreamingAllowed) {
        playableSongs.isNotEmpty() && (isStreamingAllowed || playableSongs.any { it.isFullyCached })
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = {
            if (isEffectivelyOnline) viewModel.loadAlbumDetails(forceRefresh = true, isManualRefresh = true)
            else context.showOfflineRefreshToast()
        },
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
                    contentPadding = PaddingValues(bottom = 16.dp + LocalMiniPlayerBottomInset.current)
                ) {
                    item(key = "album_header", contentType = "header") {
                        AlbumHeader(album = uiState.album)
                    }

                    if (uiState.songs.isNotEmpty()) {
                        item(key = "action_row", contentType = "action_row") {
                            ActionButtonsRow(
                                onPlayAll = {
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(mediaItems, 0, shuffle = false, queueTitle = uiState.album?.name)
                                    }
                                },
                                onShuffle = {
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(
                                            mediaItems,
                                            mediaItems.indices.random(),
                                            shuffle = true,
                                            queueTitle = uiState.album?.name
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

                    discGroups.forEach { (discNum, songsInDisc) ->
                        if (isMultiDisc) {
                            item(key = "disc_header_$discNum", contentType = "disc_header") {
                                DiscSectionHeader(
                                    discNumber = discNum,
                                    discTitle = uiState.album?.discTitles?.find { it.disc == discNum }?.title,
                                    songs = songsInDisc,
                                    isStreamingAllowed = isStreamingAllowed,
                                    transcodeIncompatible = transcodeIncompatible,
                                    onPlayDisc = {
                                        val discPlayable = songsInDisc.filter {
                                            MediaFormatUtils.isSongPlayable(it, transcodeIncompatible)
                                        }
                                        val mediaItems = discPlayable.map { mediaItemConverter.toMediaItem(it) }
                                        if (mediaItems.isNotEmpty()) {
                                            val queueTitle = uiState.album?.let { album ->
                                                val discName = album.discTitles.find { it.disc == discNum }?.title
                                                val discLabel = if (!discName.isNullOrBlank()) {
                                                    context.getString(R.string.disc_named_format, discNum, discName)
                                                } else {
                                                    context.getString(R.string.disc_format, discNum)
                                                }
                                                "${album.name} • $discLabel"
                                            }
                                            playbackController.play(mediaItems, 0, shuffle = false, queueTitle = queueTitle)
                                        }
                                    }
                                )
                            }
                        }

                        itemsIndexed(
                            items = songsInDisc,
                            key = { _, song -> song.id },
                            contentType = { _, _ -> "song_item" }
                        ) { index, song ->
                            SongListItem(
                                song = song,
                                isCurrent = song.id == currentSongId,
                                isEffectivelyOnline = isEffectivelyOnline,
                                isStreamingAllowed = isStreamingAllowed,
                                transcodeIncompatible = transcodeIncompatible,
                                trackNumberDisplay = (song.track ?: (index + 1)).toString(),
                                strings = songStrings,
                                onClick = {
                                    val playIndex = playableSongs.indexOfFirst { it.id == song.id }
                                    if (playIndex != -1) {
                                        val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                        if (mediaItems.isNotEmpty()) {
                                            playbackController.play(mediaItems, playIndex, queueTitle = uiState.album?.name)
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
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun AlbumHeader(album: Album?) {
    val songCount = album?.songCount ?: 0
    MediaDetailHeader(
        coverArtId = album?.coverArt,
        title = album?.name ?: "",
        subtitle = album?.artist ?: stringResource(R.string.unknown_artist),
        metaText = "${pluralStringResource(R.plurals.songs_count, songCount, songCount)} • " +
            FormatUtils.formatDuration(album?.duration ?: 0)
    )
}

@Composable
fun DiscSectionHeader(
    discNumber: Int,
    discTitle: String?,
    songs: List<Song>,
    isStreamingAllowed: Boolean,
    transcodeIncompatible: Boolean,
    onPlayDisc: () -> Unit
) {
    val playableSongs = remember(songs, transcodeIncompatible) {
        songs.filter { de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible) }
    }
    val isPlayEnabled = remember(playableSongs, isStreamingAllowed) {
        playableSongs.isNotEmpty() && (isStreamingAllowed || playableSongs.any { it.isFullyCached })
    }
    val totalDuration = remember(songs) { songs.sumOf { it.duration ?: 0 } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (!discTitle.isNullOrBlank()) {
                    stringResource(R.string.disc_named_format, discNumber, discTitle)
                } else {
                    stringResource(R.string.disc_format, discNumber)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.disc_summary_format,
                    songs.size,
                    FormatUtils.formatDuration(totalDuration)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(
            onClick = onPlayDisc,
            enabled = isPlayEnabled,
            modifier = Modifier.alpha(if (isPlayEnabled) 1.0f else 0.38f)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.cd_play_disc),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
