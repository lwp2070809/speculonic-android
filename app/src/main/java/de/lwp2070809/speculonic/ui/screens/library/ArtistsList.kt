package de.lwp2070809.speculonic.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.network.model.Artist
import de.lwp2070809.speculonic.ui.components.FastScroller
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.buildRequest

sealed class ArtistListItem {
    abstract val groupChar: String

    data class Header(
        val char: String,
        val count: Int,
        val isCollapsed: Boolean
    ) : ArtistListItem() {
        override val groupChar: String get() = char
    }

    data class ArtistItem(
        val artist: Artist,
        val char: String
    ) : ArtistListItem() {
        override val groupChar: String get() = char
    }
}

@Composable
fun ArtistsList(
    artists: List<Artist>, 
    onArtistClick: (Artist) -> Unit
) {
    val coverRequester = LocalCoverArtRequester.current
    val gridState = rememberLazyGridState()
    
    val sortedArtists = remember(artists) {
        artists.sortedBy { it.name.uppercase() }
    }

    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    
    val groupedArtists = remember(sortedArtists) {
        sortedArtists.groupBy { artist ->
            val firstChar = artist.name.trim().take(1).uppercase()
            if (firstChar.isEmpty()) "#" else firstChar
        }
    }

    val flatListItems = remember(groupedArtists, collapsedGroups) {
        val list = mutableListOf<ArtistListItem>()
        groupedArtists.forEach { (char, artistList) ->
            val isCollapsed = collapsedGroups.contains(char)
            list.add(ArtistListItem.Header(char, artistList.size, isCollapsed))
            if (!isCollapsed) {
                artistList.forEach { artist ->
                    list.add(ArtistListItem.ArtistItem(artist, char))
                }
            }
        }
        list
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 180.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 64.dp, top = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = flatListItems,
                key = { _, item ->
                    when (item) {
                        is ArtistListItem.Header -> "header_${item.char}"
                        is ArtistListItem.ArtistItem -> "artist_${item.artist.id}"
                    }
                },
                span = { _, item ->
                    when (item) {
                        is ArtistListItem.Header -> GridItemSpan(maxLineSpan)
                        is ArtistListItem.ArtistItem -> GridItemSpan(1)
                    }
                }
            ) { _, item ->
                when (item) {
                    is ArtistListItem.Header -> {
                        ListItem(
                            headlineContent = { 
                                Text(
                                    text = item.char,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ) 
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${item.count}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    Icon(
                                        imageVector = if (item.isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                        contentDescription = if (item.isCollapsed) "Expand" else "Collapse",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    collapsedGroups = if (item.isCollapsed) {
                                        collapsedGroups - item.char
                                    } else {
                                        collapsedGroups + item.char
                                    }
                                }
                        )
                    }
                    is ArtistListItem.ArtistItem -> {
                        val artist = item.artist
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtistClick(artist) }
                        ) {
                            ListItem(
                                headlineContent = { Text(artist.name, maxLines = 1) },
                                supportingContent = { 
                                    val albumCount = artist.albumCount ?: 0
                                    Text(
                                        text = pluralStringResource(R.plurals.albums_count, albumCount, albumCount),
                                        maxLines = 1
                                    ) 
                                },
                                leadingContent = {
                                    val model = remember(artist.coverArt) {
                                        coverRequester.buildRequest(artist.coverArt, preferLocal = true)
                                    }
                                    AsyncImage(
                                        model = model,
                                        contentDescription = artist.name,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        FastScroller(
            gridState = gridState,
            flatItems = flatListItems,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 16.dp)
        )
    }
}
