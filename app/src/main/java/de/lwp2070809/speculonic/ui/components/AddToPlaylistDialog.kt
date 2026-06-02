package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.network.model.Playlist
import de.lwp2070809.speculonic.network.model.PlaylistAddResult
import de.lwp2070809.speculonic.network.model.Song
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistDialog(
    song: Song,
    repository: SubsonicRepository,
    onDismiss: () -> Unit,
    onResult: (PlaylistAddResult) -> Unit
) {
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var processingPlaylistId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        playlists = repository.getPlaylists()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = { if (processingPlaylistId == null) onDismiss() },
        title = { Text(stringResource(R.string.select_playlist)) },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (playlists.isEmpty()) {
                    Text(
                        text = "No playlists found",
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                    )
                } else {
                    LazyColumn {
                        items(playlists) { playlist ->
                            val isProcessing = processingPlaylistId == playlist.id
                            ListItem(
                                headlineContent = { 
                                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                                },
                                supportingContent = { 
                                    Text("${playlist.songCount ?: 0} songs") 
                                },
                                leadingContent = {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.clickable(enabled = processingPlaylistId == null) {
                                    processingPlaylistId = playlist.id
                                    scope.launch {
                                        try {
                                            
                                            val existingSongs = repository.getPlaylist(playlist.id)
                                            if (existingSongs.any { it.id == song.id }) {
                                                onResult(PlaylistAddResult.ALREADY_EXISTS)
                                                onDismiss()
                                            } else {
                                                
                                                val success = repository.addToPlaylist(playlist.id, song.id)
                                                onResult(if (success) PlaylistAddResult.SUCCESS else PlaylistAddResult.ERROR)
                                                onDismiss()
                                            }
                                        } finally {
                                            processingPlaylistId = null
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = processingPlaylistId == null
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
