package de.lwp2070809.speculonic.ui.screens.details

import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.lwp2070809.speculonic.ui.components.MediaDetailHeader
import de.lwp2070809.speculonic.ui.components.showOfflineRefreshToast
import de.lwp2070809.speculonic.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.network.model.Album
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    viewModel: ArtistDetailViewModel,
    topBarState: TopBarState,
    isEffectivelyOnline: Boolean,
    onAlbumClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    
    LaunchedEffect(uiState.artist) {
        topBarState.update(
            title = uiState.artist?.name ?: "",
            showBack = true,
            onBackClickOverride = onBackClick,
            token = screenToken
        )
    }

    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.artist != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = {
            if (isEffectivelyOnline) viewModel.loadArtistDetails(forceRefresh = true, isManualRefresh = true)
            else context.showOfflineRefreshToast()
        },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (uiState.isLoading && uiState.artist == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.error != null && uiState.artist == null) {
                Text(text = uiState.error!!, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
            } else if (uiState.artist != null) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 0.dp,
                        bottom = 16.dp + LocalMiniPlayerBottomInset.current
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                ) {
                    item(
                        key = "artist_header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = { "artist_header" }
                    ) {
                        ArtistHeader(artist = uiState.artist)
                    }

                    items(
                        items = uiState.albums,
                        key = { it.id },
                        contentType = { "album_item" }
                    ) { album ->
                        AlbumItem(album = album, onClick = { onAlbumClick(album.id) })
                    }
                }
            }
        }
    }
}


@Composable
fun ArtistHeader(artist: Artist?) {
    val albumCount = artist?.albumCount ?: 0
    MediaDetailHeader(
        coverArtId = artist?.coverArt,
        title = artist?.name ?: "",
        metaText = pluralStringResource(R.plurals.albums_count, albumCount, albumCount),
        contentPadding = PaddingValues(vertical = 16.dp)
    )
}

@Composable
fun AlbumItem(album: Album, onClick: () -> Unit) {
    val coverRequester = LocalCoverArtRequester.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        val model = remember(album.coverArt) {
            coverRequester.buildCoverArtRequest(album.coverArt, context, preferLocal = true, size = 400)
        }
        AsyncImage(
            model = model,
            contentDescription = album.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (album.year != null) {
            Text(
                text = album.year.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


