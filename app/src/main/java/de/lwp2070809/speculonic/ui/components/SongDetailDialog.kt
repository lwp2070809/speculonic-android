package de.lwp2070809.speculonic.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.components.songdetail.Id3Tab
import de.lwp2070809.speculonic.ui.components.songdetail.LocalDbTab
import de.lwp2070809.speculonic.ui.components.songdetail.RemoteTab
import de.lwp2070809.speculonic.ui.composition.LocalNavigator
import de.lwp2070809.speculonic.ui.navigation.AppRoute
import kotlinx.coroutines.launch

@Composable
fun SongDetailDialog(
    song: Song,
    onDismiss: () -> Unit,
    onNavigateToAlbum: ((String) -> Unit)? = null,
    onNavigateToArtist: ((String) -> Unit)? = null,
    viewModel: SongDetailViewModel = hiltViewModel(key = "song_detail_${song.id}")
) {
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current

    val handleNavigateToAlbum: ((String) -> Unit)? = onNavigateToAlbum ?: navigator?.let { nav ->
        { albumId ->
            onDismiss()
            nav.navigate(AppRoute.AlbumDetail(albumId))
        }
    }
    val handleNavigateToArtist: ((String) -> Unit)? = onNavigateToArtist ?: navigator?.let { nav ->
        { artistId ->
            onDismiss()
            nav.navigate(AppRoute.ArtistDetail(artistId))
        }
    }

    val songEntity by remember(song.id, viewModel) {
        viewModel.getSongEntityByIdFlow(song.id)
    }.collectAsStateWithLifecycle(initialValue = null)
    val uiState by viewModel.uiState.collectAsState()

    val pagerState = rememberPagerState(pageCount = { 3 })
    val failedToFetchRemoteMsg = stringResource(R.string.failed_to_fetch_remote)

    val localUri = songEntity?.takeIf { it.id == song.id && it.isFullyCached }?.localUri
    DisposableEffect(viewModel, song.id, localUri) {
        viewModel.bindLocalFile(song.id, localUri)
        onDispose { viewModel.bindLocalFile(song.id, null) }
    }
    LaunchedEffect(viewModel, song.id, pagerState.currentPage, localUri) {
        if (pagerState.currentPage == 1 && localUri != null) {
            viewModel.loadId3Metadata(song.id, localUri, songEntity?.suffix, songEntity?.isTranscoded == true)
        }
    }

    LaunchedEffect(song.id, pagerState.currentPage) {
        val shouldFetchRemote = uiState.songId != song.id ||
            (uiState.remoteSong == null && !uiState.remoteLoading)
        if (pagerState.currentPage == 2 && shouldFetchRemote) {
            viewModel.fetchRemoteSong(song.id, failedToFetchRemoteMsg)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_details)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.general_info)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.metadata_tab)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        text = { Text(stringResource(R.string.remote_tab)) }
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp)
                    ) {
                        when (page) {
                            0 -> LocalDbTab(
                                songEntity = songEntity,
                                sha1 = if (uiState.localUri == localUri) uiState.sha1 else Sha1State.Ready,
                                onCalculate = { localUri?.let { viewModel.loadSha1(song.id, it) } },
                                onAlbumClick = handleNavigateToAlbum,
                                onArtistClick = handleNavigateToArtist
                            )
                            1 -> Id3Tab(uiState.id3Metadata, localUri != null, uiState.id3Limit)
                            2 -> RemoteTab(songEntity, uiState.remoteSong, uiState.remoteLoading, uiState.remoteError)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}
