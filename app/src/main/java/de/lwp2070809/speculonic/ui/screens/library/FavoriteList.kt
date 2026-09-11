package de.lwp2070809.speculonic.ui.screens.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalMediaItemConverter
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteList(
    songs: List<Song>, 
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    onDownloadClick: (Song) -> Unit,
    onDownloadAllClick: () -> Unit,
    onStarClick: (String, Boolean, (Boolean) -> Unit) -> Unit = { _, _, _ -> }
) {
    val mediaItemConverter = LocalMediaItemConverter.current
    val downloadController = LocalDownloadController.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }
    val failedMessage = stringResource(R.string.failed_to_fetch_remote)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeDownloads by DownloadTracker.activeDownloadIds.collectAsState()
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    val isAnyDownloading = remember(activeDownloads, songs) {
        songs.any { activeDownloads.contains(it.id) }
    }
    val isAllDownloaded = remember(downloadedIds, songs) {
        songs.isNotEmpty() && songs.all { it.isFullyCached }
    }

    val preferencesManager = remember { de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context) }
    val transcodeIncompatible by preferencesManager.transcodeIncompatibleFormats.collectAsState(initial = false)

    val playableSongs = remember(songs, transcodeIncompatible) {
        songs.filter { de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible) }
    }

    val isPlayActionsEnabled = remember(playableSongs, downloadedIds, isStreamingAllowed) {
        playableSongs.isNotEmpty() && (isStreamingAllowed || playableSongs.any { it.isFullyCached })
    }

    var lastStarClickTime by remember { mutableLongStateOf(0L) }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item {
                Text(
                    text = pluralStringResource(R.plurals.songs_count, songs.size, songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (songs.isNotEmpty()) {
                item {
                    ActionButtonsRow(
                        onPlayAll = {
                            val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                            if (mediaItems.isNotEmpty()) {
                                playbackController.play(mediaItems, 0, queueTitle = "Favorite")
                            }
                        },
                        onShuffle = {
                            val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                            if (mediaItems.isNotEmpty()) {
                                playbackController.play(mediaItems, mediaItems.indices.random(), shuffle = true, queueTitle = "Favorite")
                            }
                        },
                        onDownloadAll = onDownloadAllClick,
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

            itemsIndexed(songs, key = { _, song -> song.id }) { _, song ->
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
                                playbackController.play(mediaItems, playIndex, queueTitle = "Favorite")
                            }
                        }
                    },
                    onStarClick = { star ->
                        val now = System.currentTimeMillis()
                        if (now - lastStarClickTime >= 500) {
                            lastStarClickTime = now
                            onStarClick(song.id, star) { success ->
                                if (!success) {
                                    Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            }
                        }
                    },
                    onDownloadClick = { downloadController.downloadSong(song) },
                    onRemoveDownloadClick = { downloadController.removeDownload(song.id) }
                )
            }
        }
    }
}
