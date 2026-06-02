package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import de.lwp2070809.speculonic.R

@Composable
fun QueueView(
    queue: List<MediaItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in queue.indices) {
            listState.scrollToItem(currentIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.queue),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = queue,
                key = { _, item -> item.mediaId },
                contentType = { _, _ -> "queueItem" }
            ) { index, item ->
                ListItem(
                    headlineContent = { 
                        val title = item.mediaMetadata.title?.toString() ?: ""
                        Text(
                            text = title.ifEmpty { stringResource(R.string.not_playing) },
                            fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        ) 
                    },
                    supportingContent = { 
                        val artist = item.mediaMetadata.artist?.toString() ?: ""
                        Text(artist.ifEmpty { stringResource(R.string.not_playing_artist) })
                    },
                    leadingContent = {
                        if (index == currentIndex) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("${index + 1}", modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemoveItem(index) }) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    modifier = Modifier.clickable { onItemClick(index) }
                )
            }
        }
    }
}
