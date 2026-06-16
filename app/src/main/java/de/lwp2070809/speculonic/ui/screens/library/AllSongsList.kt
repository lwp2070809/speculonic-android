package de.lwp2070809.speculonic.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun AllSongsList(
    songsPaged: LazyPagingItems<Song>,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }

    val context = LocalContext.current
    val downloadController = remember(repository) { DownloadController(context, repository) }
    val scope = rememberCoroutineScope()
    val allSongsTitle = stringResource(R.string.all_songs)

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(
                count = songsPaged.itemCount,
                key = { index -> songsPaged.peek(index)?.id ?: "placeholder_index_$index" },
                contentType = { "song" }
            ) { index ->
                val song = songsPaged[index]
                if (song != null) {
                    SongListItem(
                        song = song,
                        isCurrent = song.id == currentSongId,
                        isOnline = isOnline,
                        isEffectivelyOnline = isEffectivelyOnline,
                        onClick = {
                            scope.launch {
                                val windowStart = index
                                val windowEnd = minOf(index + 100, songsPaged.itemCount)
                                val windowSongs = (windowStart until windowEnd).mapNotNull { songsPaged.peek(it) }

                                val mediaItems = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    windowSongs.map { it.toMediaItem(repository) }
                                }
                                playbackController.play(mediaItems, 0, queueTitle = allSongsTitle)
                            }
                        },
                        onStarClick = { star ->
                            scope.launch {
                                repository.starSong(song.id, star)
                            }
                        },
                        onDownloadClick = { downloadController.downloadSong(song) },
                        onRemoveDownloadClick = { downloadController.removeDownload(song.id) }
                    )
                } else {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small)
                    )
                }
            }

            songsPaged.apply {
                when {
                    loadState.refresh is LoadState.Loading -> {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    loadState.append is LoadState.Loading -> {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
