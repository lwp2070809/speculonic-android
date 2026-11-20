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
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
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
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onClose: () -> Unit,
    backHandlerEnabled: Boolean = true
) {
    val mediaItemConverter = LocalMediaItemConverter.current
    val downloadController = LocalDownloadController.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsStateWithLifecycle()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }

    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val resultIds = remember(uiState.results.song) { uiState.results.song.map { it.id }.distinct() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, resultIds, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.observeResultMetadata(resultIds) }
    }
    val transcodeIncompatible = uiState.transcodeIncompatible
    
    var searchBackProgress by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler(enabled = backHandlerEnabled) { progressFlow ->
        try {
            progressFlow.collect { event ->
                searchBackProgress = event.progress
            }
            onClose()
        } finally {
            searchBackProgress = 0f
        }
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - (searchBackProgress * 0.08f)
                scaleX = scale
                scaleY = scale
                translationX = -searchBackProgress * 48.dp.toPx()
            }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                placeholder = {
                    val hintRes = if (isEffectivelyOnline) {
                        R.string.search_hint
                    } else {
                        R.string.search_offline_hint
                    }
                    Text(text = stringResource(hintRes))
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear))
                        }
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val hasNoResults = uiState.results.song.isEmpty() &&
                uiState.results.album.isEmpty() &&
                uiState.results.artist.isEmpty()
            val showOfflineEmptyGuide = !isEffectivelyOnline && uiState.query.isBlank()
            val showNoMatchEmpty = uiState.query.isNotBlank() && !uiState.isLoading && hasNoResults

            when {
                showOfflineEmptyGuide -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_symbol_wifi_off),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.search_offline_local_tip),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                showNoMatchEmpty -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!isEffectivelyOnline || uiState.isLocalFallback) {
                            val bannerRes = if (!isEffectivelyOnline) {
                                R.string.search_offline_local_tip
                            } else {
                                R.string.search_online_failed_fallback_local
                            }
                            Text(
                                text = stringResource(bannerRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                else -> {
                    val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val imePadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                    val bottomInset = maxOf(navigationBarsPadding + 80.dp, imePadding + 16.dp)

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = bottomInset
                        )
                    ) {
                        if ((!isEffectivelyOnline || uiState.isLocalFallback) && uiState.query.isNotBlank()) {
                            item(key = "local_search_notice", contentType = "search_notice") {
                                val noticeRes = if (!isEffectivelyOnline) {
                                    R.string.search_offline_local_tip
                                } else {
                                    R.string.search_online_failed_fallback_local
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(noticeRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        if (uiState.results.artist.isNotEmpty()) {
                            item(key = "header_artists", contentType = "section_header") {
                                SectionHeader(stringResource(R.string.artists))
                            }
                            items(
                                items = uiState.results.artist,
                                key = { "artist_${it.id}" },
                                contentType = { "artist_item" }
                            ) { artist ->
                                ArtistSearchItem(artist) {
                                    onArtistClick(artist.id)
                                }
                            }
                        }

                        if (uiState.results.album.isNotEmpty()) {
                            item(key = "header_albums", contentType = "section_header") {
                                SectionHeader(stringResource(R.string.albums))
                            }
                            items(
                                items = uiState.results.album,
                                key = { "album_${it.id}" },
                                contentType = { "album_item" }
                            ) { album ->
                                AlbumSearchItem(album) {
                                    onAlbumClick(album.id)
                                }
                            }
                        }

                        if (uiState.results.song.isNotEmpty()) {
                            item(key = "header_songs", contentType = "section_header") {
                                SectionHeader(stringResource(R.string.songs))
                            }
                            items(
                                items = uiState.results.song,
                                key = { "song_${it.id}" },
                                contentType = { "song_item" }
                            ) { song ->
                                SongListItem(
                                    song = song,
                                    isCurrent = song.id == currentSongId,
                                    isEffectivelyOnline = isEffectivelyOnline,
                                    isStreamingAllowed = isStreamingAllowed,
                                    transcodeIncompatible = transcodeIncompatible,
                                    onClick = {
                                        val playableSongs = uiState.results.song.filter {
                                            de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(
                                                it,
                                                transcodeIncompatible
                                            )
                                        }
                                        val targetIndex = playableSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)

                                        val mediaItems = playableSongs.map { mediaItemConverter.toMediaItem(it) }
                                        if (mediaItems.isNotEmpty()) {
                                            playbackController.play(mediaItems, targetIndex)
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
        coverRequester.buildCoverArtRequest(artist.coverArt, context, preferLocal = true, size = 200)
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
        coverRequester.buildCoverArtRequest(album.coverArt, context, preferLocal = true, size = 200)
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
