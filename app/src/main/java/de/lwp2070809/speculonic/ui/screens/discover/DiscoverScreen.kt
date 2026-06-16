package de.lwp2070809.speculonic.ui.screens.discover

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel, 
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onViewAllFavoriteSongs: () -> Unit = {},
    onViewAllFavoriteAlbums: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onConfigureServerClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current

    val isEmpty = uiState.pinnedPlaylists.isEmpty() &&
            uiState.recentlyAdded.isEmpty() &&
            uiState.randomAlbums.isEmpty() &&
            uiState.mostPlayed.isEmpty() &&
            uiState.favoriteSongs.isEmpty() &&
            uiState.favoriteAlbums.isEmpty()

    var forceShowOfflineMessage by remember { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (!isOnline && !uiState.isInitialLoadComplete) {
            forceShowOfflineMessage = true
            delay(1000)
            forceShowOfflineMessage = false
        } else if (isOnline) {
            forceShowOfflineMessage = false
        }
    }

    val showEmptyState = isEmpty && !uiState.isLoading && uiState.isInitialLoadComplete

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { if (isOnline) viewModel.refreshData() },
        modifier = Modifier.fillMaxSize()
    ) {
        val showOffline = forceShowOfflineMessage || (!isOnline && showEmptyState && isEmpty)
        
        if (showOffline) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.offline_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (isEmpty && uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.first_sync_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (showEmptyState && isEmpty) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.no_content_available),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.refreshData() },
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(stringResource(R.string.sync_now))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onConfigureServerClick,
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text(stringResource(R.string.settings))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                
                if (uiState.pinnedPlaylists.isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.pinned_playlists))
                        PlaylistRow(
                            playlists = uiState.pinnedPlaylists,
                            onPlaylistClick = { onPlaylistClick(it) }
                        )
                    }
                }

                
                if (uiState.favoriteSongs.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.favorite_songs),
                            onViewAllClick = onViewAllFavoriteSongs
                        )
                        FavoriteSongsRow(
                            songs = uiState.favoriteSongs,
                            isOnline = isOnline,
                                isEffectivelyOnline = isEffectivelyOnline,
                            onSongClick = { song ->
                                viewModel.playFavoriteSong(song, playbackController)
                            }
                        )
                    }
                }

                
                if (uiState.favoriteAlbums.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.favorite_albums),
                            onViewAllClick = onViewAllFavoriteAlbums
                        )
                        AlbumRow(
                            albums = uiState.favoriteAlbums,
                            onAlbumClick = { onAlbumClick(it.id) }
                        )
                    }
                }

                if (uiState.recentlyAdded.isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.recently_added))
                        AlbumRow(
                            albums = uiState.recentlyAdded,
                            onAlbumClick = { onAlbumClick(it.id) }
                        )
                    }
                }

                if (uiState.randomAlbums.isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.random_albums))
                        AlbumRow(
                            albums = uiState.randomAlbums,
                            onAlbumClick = { onAlbumClick(it.id) }
                        )
                    }
                }

                if (uiState.mostPlayed.isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.most_played))
                        AlbumRow(
                            albums = uiState.mostPlayed,
                            onAlbumClick = { onAlbumClick(it.id) }
                        )
                    }
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    onViewAllClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        if (onViewAllClick != null) {
            TextButton(
                onClick = onViewAllClick,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(stringResource(R.string.view_all))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun FavoriteSongsRow(
    songs: List<Song>,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onSongClick: (Song) -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val playbackStateState = playbackController.playbackState.collectAsState()
    val currentSongId by remember { derivedStateOf { playbackStateState.value.currentSongId } }

    val context = LocalContext.current
    val downloadController = remember(repository) { de.lwp2070809.speculonic.playback.DownloadController(context, repository) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        songs.take(3).forEach { song ->
            SongListItem(
                song = song,
                isCurrent = song.id == currentSongId,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                onClick = { onSongClick(song) },
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

@Composable
fun AlbumRow(
    albums: List<Album>, 
    onAlbumClick: (Album) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(
                album = album, 
                onClick = { onAlbumClick(album) }
            )
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit) {
    val repository = LocalSubsonicRepository.current
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        val context = LocalContext.current
        val model = remember(album.coverArt) {
            repository.buildCoverArtRequest(album.coverArt, context, preferLocal = true)
        }
        
        AsyncImage(
            model = model,
            contentDescription = album.name,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = album.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
fun PlaylistRow(
    playlists: List<de.lwp2070809.speculonic.network.model.Playlist>,
    onPlaylistClick: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist = playlist, 
                onClick = { onPlaylistClick(playlist.id) }
            )
        }
    }
}

@Composable
fun PlaylistCard(playlist: de.lwp2070809.speculonic.network.model.Playlist, onClick: () -> Unit) {
    val repository = LocalSubsonicRepository.current
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        val context = LocalContext.current
        val model = remember(playlist.coverArt) {
            repository.buildCoverArtRequest(playlist.coverArt, context, preferLocal = true)
        }
        
        AsyncImage(
            model = model,
            contentDescription = playlist.name,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
        val subtitle = playlist.owner ?: ""
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
