package de.lwp2070809.speculonic.ui.screens.library

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.ActionButtonsRow
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteList(
    songs: List<Song>, 
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    onDownloadClick: (Song) -> Unit,
    onDownloadAllClick: () -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val context = LocalContext.current
    val downloadController = remember(repository) { DownloadController(context, repository) }
    val scope = rememberCoroutineScope()
    val activeDownloads by DownloadTracker.activeDownloadIds.collectAsState()
    val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
    val isAnyDownloading = remember(activeDownloads, songs) {
        songs.any { activeDownloads.contains(it.id) }
    }
    val isAllDownloaded = remember(downloadedIds, songs) {
        songs.isNotEmpty() && songs.all { it.isFullyCached || downloadedIds.contains(it.id) }
    }

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
                            val mediaItems = songs.map { it.toMediaItem(repository) }
                            playbackController.play(mediaItems, 0, queueTitle = "Favorite")
                        },
                        onShuffle = {
                            val mediaItems = songs.map { it.toMediaItem(repository) }
                            playbackController.play(mediaItems, 0, shuffle = true, queueTitle = "Favorite")
                        },
                        onDownloadAll = onDownloadAllClick,
                        isOnline = isOnline,
                        isEffectivelyOnline = isEffectivelyOnline,
                        isDownloading = isAnyDownloading,
                        isDownloadEnabled = !isAllDownloaded
                    )
                }
            }

            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongListItem(
                    song = song,
                    isOnline = isOnline,
                                isEffectivelyOnline = isEffectivelyOnline,
                    onClick = {
                        val mediaItems = songs.map { it.toMediaItem(repository) }
                        playbackController.play(mediaItems, index, queueTitle = "Favorite")
                    },
                    onStarClick = { star ->
                        scope.launch {
                            repository.starSong(song.id, star)
                        }
                    },
                    onDownloadClick = { downloadController.downloadSong(song) },
                    onRemoveDownloadClick = { downloadController.removeDownload(song.id) }
                )
            }
        }
    }
}

