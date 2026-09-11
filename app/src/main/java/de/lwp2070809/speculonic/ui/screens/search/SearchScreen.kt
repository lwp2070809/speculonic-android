package de.lwp2070809.speculonic.ui.screens.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalMediaItemConverter
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onClose: () -> Unit
) {
    val mediaItemConverter = LocalMediaItemConverter.current
    val downloadController = LocalDownloadController.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val preferencesManager = remember { de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context) }
    val transcodeIncompatible by preferencesManager.transcodeIncompatibleFormats.collectAsState(initial = false)
    
    BackHandler(onBack = onClose)

    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = { 
                    Text(text = stringResource(R.string.search_hint)) 
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = isEffectivelyOnline,
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isEffectivelyOnline && uiState.results.song.isEmpty() && uiState.results.album.isEmpty() && uiState.results.artist.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi_off),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.search_offline_warning),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = navigationBarsPadding + 80.dp
                    )
                ) {
                    if (uiState.results.artist.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.artists)) }
                        items(uiState.results.artist) { artist ->
                            ArtistSearchItem(artist) {
                                onArtistClick(artist.id)
                            }
                        }
                    }

                    if (uiState.results.album.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.albums)) }
                        items(uiState.results.album) { album ->
                            AlbumSearchItem(album) {
                                onAlbumClick(album.id)
                            }
                        }
                    }

                    if (uiState.results.song.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.songs)) }
                        items(uiState.results.song) { song ->
                            SongListItem(
                                song = song,
                                isCurrent = song.id == currentSongId,
                                isOnline = isOnline,
                                isEffectivelyOnline = isEffectivelyOnline,
                                isStreamingAllowed = isStreamingAllowed,
                                transcodeIncompatible = transcodeIncompatible,
                                onClick = {
                                    val songIndex = uiState.results.song.indexOf(song)
                                    val subsequentSongs = if (songIndex != -1) uiState.results.song.drop(songIndex) else listOf(song)
                                    val playableSongs = subsequentSongs.filter { de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(it, transcodeIncompatible) }
                                    val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                    if (mediaItems.isNotEmpty()) {
                                        playbackController.play(mediaItems, 0)
                                    }
                                },
                                onStarClick = { star ->
                                    viewModel.toggleStarSong(song.id, star)
                                },
                                onDownloadClick = { downloadController.downloadSong(song) },
                                onRemoveDownloadClick = { downloadController.removeDownload(song.id) }
                            )
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
fun ArtistSearchItem(artist: Artist, onClick: () -> Unit) {
    val coverRequester = LocalCoverArtRequester.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val model = androidx.compose.runtime.remember(artist.coverArt) {
        coverRequester.buildCoverArtRequest(artist.coverArt, context, preferLocal = true)
    }
    ListItem(
        headlineContent = { Text(artist.name) },
        leadingContent = {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
fun AlbumSearchItem(album: Album, onClick: () -> Unit) {
    val coverRequester = LocalCoverArtRequester.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val model = androidx.compose.runtime.remember(album.coverArt) {
        coverRequester.buildCoverArtRequest(album.coverArt, context, preferLocal = true)
    }
    ListItem(
        headlineContent = { Text(album.name) },
        supportingContent = { Text(album.artist ?: "") },
        leadingContent = {
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}
