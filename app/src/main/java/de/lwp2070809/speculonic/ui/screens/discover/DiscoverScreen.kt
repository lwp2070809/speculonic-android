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
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalIsDualPane
import de.lwp2070809.speculonic.ui.composition.LocalNavigator
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.navigation.AppRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel, 
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onViewAllFavoriteSongs: () -> Unit = {},
    onViewAllFavoriteAlbums: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onConfigureServerClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val playbackController = LocalPlaybackController.current
    val isDualPane = LocalIsDualPane.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val preferencesManager = remember { de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context) }
    val transcodeIncompatible by preferencesManager.transcodeIncompatibleFormats.collectAsState(initial = false)

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
        onRefresh = { if (isEffectivelyOnline) viewModel.refreshData() },
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
                    androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi_off),
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
                    painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_library_music),
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
                    modifier = Modifier.fillMaxWidth(0.7f),
                    enabled = isEffectivelyOnline
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
                            isStreamingAllowed = isStreamingAllowed,
                            transcodeIncompatible = transcodeIncompatible,
                            onSongClick = { song ->
                                if (isDualPane) {
                                    navigator?.navigate(AppRoute.PlaybackQueue)
                                }
                                viewModel.playFavoriteSong(song)
                            },
                            onStarClick = { songId, star ->
                                viewModel.toggleStarSong(songId, star)
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
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

