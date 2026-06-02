package de.lwp2070809.speculonic.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.paging.compose.collectAsLazyPagingItems
import de.lwp2070809.speculonic.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedTabIndex = uiState.selectedTabIndex
    
    val tabs = listOf(
        R.string.albums,
        R.string.playlists,
        R.string.songs,
        R.string.artists
    )

    val pagerState = rememberPagerState(
        initialPage = selectedTabIndex,
        pageCount = { tabs.size }
    )

    val allSongsPaged = uiState.allSongsPaged?.collectAsLazyPagingItems()

    LaunchedEffect(selectedTabIndex) {
        if (selectedTabIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(selectedTabIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedTabIndex) {
            viewModel.setSelectedTabIndex(pagerState.currentPage)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            tabs.forEachIndexed { index, titleRes ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { viewModel.setSelectedTabIndex(index) },
                    text = { Text(text = stringResource(titleRes)) }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { 
                if (isOnline) {
                    when (selectedTabIndex) {
                        0 -> viewModel.refreshAlbums()
                        1 -> viewModel.refreshPlaylists()
                        2 -> viewModel.refreshQuickly()
                        3 -> viewModel.refreshArtists()
                        else -> viewModel.refreshQuickly()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible = (uiState.isRefreshing || uiState.isSyncing) && uiState.syncProgress != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = uiState.syncProgress ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 3
                        ) { page ->
                            when (page) {
                                0 -> AlbumGrid(
                                    albums = uiState.albums,
                                    onAlbumClick = onAlbumClick
                                )
                                1 -> PlaylistList(
                                    playlists = uiState.playlists, 
                                    onPlaylistClick = onPlaylistClick,
                                    onCreatePlaylist = { viewModel.createPlaylist(it) },
                                    onDeletePlaylist = { viewModel.deletePlaylist(it) },
                                    isOnline = isOnline,
                                    isEffectivelyOnline = isEffectivelyOnline
                                )
                                2 -> allSongsPaged?.let { songsPaged ->
                                    AllSongsList(
                                        songsPaged = songsPaged,
                                        isOnline = isOnline,
                                        isEffectivelyOnline = isEffectivelyOnline
                                    )
                                }
                                3 -> ArtistsList(
                                    artists = uiState.artists, 
                                    onArtistClick = { artist ->
                                        onArtistClick(artist.id)
                                    }
                                ) 
                            }
                        }
                    }
                }

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
                    )
                }
            }
        }
    }

    if (uiState.showSafetyGuardConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelSafetyGuard() },
            title = { Text(stringResource(R.string.safety_guard_title)) },
            text = { Text(uiState.safetyGuardMessage ?: stringResource(R.string.safety_guard_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSafetyGuard() }) {
                    Text(stringResource(R.string.force_sync), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelSafetyGuard() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
