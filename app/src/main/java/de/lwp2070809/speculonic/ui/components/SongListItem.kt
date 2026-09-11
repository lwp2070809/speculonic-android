package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.network.model.PlaylistAddResult
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.buildRequest
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun SongListItem(
    song: Song,
    isCurrent: Boolean,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onClick: () -> Unit,
    onStarClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadClick: () -> Unit = {},
    onRemoveDownloadClick: () -> Unit = {},
    transcodeIncompatible: Boolean = false,
    trailingContentOverride: @Composable (() -> Unit)? = null
) {
    val coverRequester = LocalCoverArtRequester.current
    val context = LocalContext.current

    var isStarred by remember(song.id, song.starred) { mutableStateOf(song.starred != null) }
    val isDownloaded = song.isFullyCached
    
    val isPlayable = de.lwp2070809.speculonic.util.MediaFormatUtils.isSongPlayable(song, transcodeIncompatible)

    val isEnabled = isPlayable && (isDownloaded || isStreamingAllowed)
    val alpha = if (isEnabled) 1.0f else 0.38f

    var showMenu by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    
    val successMsg = stringResource(R.string.add_to_playlist_success)
    val existsMsg = stringResource(R.string.song_already_in_playlist)
    val errorMsg = stringResource(R.string.add_to_playlist_error)
    val suffixText = remember(song.suffix) { (song.suffix ?: "UNKNOWN").uppercase() }
    val incompatibleDownloadedHint = stringResource(R.string.incompatible_downloaded_hint, suffixText)
    val unsupportedFormatHint = stringResource(R.string.unsupported_format_hint, suffixText)

    if (showDetailDialog) {
        SongDetailDialog(song = song, onDismiss = { showDetailDialog = false })
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            song = song,
            onDismiss = { showAddToPlaylistDialog = false },
            onResult = { result ->
                val message = when (result) {
                    PlaylistAddResult.SUCCESS -> successMsg
                    PlaylistAddResult.ALREADY_EXISTS -> existsMsg
                    PlaylistAddResult.ERROR -> errorMsg
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    ListItem(
        headlineContent = { 
            Row(
                modifier = Modifier.alpha(alpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
                if (isDownloaded) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        supportingContent = { 
            Text(
                text = "${song.artist ?: ""} • ${FormatUtils.formatDuration(song.duration ?: 0)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha)
            ) 
        },
        leadingContent = {
            val model = remember(song.coverArt) {
                coverRequester.buildRequest(song.coverArt, preferLocal = true)
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .alpha(alpha)
                    .size(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (isCurrent) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        }
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            if (trailingContentOverride != null) {
                Box(modifier = Modifier.alpha(alpha)) {
                    trailingContentOverride()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { 
                            isStarred = !isStarred
                            onStarClick(isStarred)
                        },
                        enabled = isEffectivelyOnline,
                        modifier = Modifier.alpha(if (isEffectivelyOnline) 1.0f else 0.38f)
                    ) {
                        Icon(
                            painter = if (isStarred) rememberVectorPainter(Icons.Default.Favorite) else androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_favorite_border),
                            contentDescription = "Star",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (isDownloaded) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_download)) },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_delete), contentDescription = null) },
                                    onClick = { 
                                        onRemoveDownloadClick()
                                        showMenu = false
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download)) },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_download), contentDescription = null) },
                                    onClick = { 
                                        onDownloadClick()
                                        showMenu = false
                                    },
                                    enabled = isStreamingAllowed
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.song_details)) },
                                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_info), contentDescription = null) },
                                onClick = { 
                                    showDetailDialog = true
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_to_playlist)) },
                                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_playlist_add), contentDescription = null) },
                                onClick = { 
                                    showAddToPlaylistDialog = true
                                    showMenu = false
                                },
                                enabled = isEffectivelyOnline
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
            .clickable {
                if (!isPlayable) {
                    val suffixText = (song.suffix ?: "UNKNOWN").uppercase()
                    val isDirectSupported = de.lwp2070809.speculonic.util.MediaFormatUtils.isDirectPlaybackSupported(song.suffix)
                    val isRawIncompatibleCached = song.isFullyCached && !song.isTranscoded && !isDirectSupported

                    val toastText = if (isRawIncompatibleCached) {
                        incompatibleDownloadedHint
                    } else {
                        unsupportedFormatHint
                    }
                    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                } else if (isDownloaded || isStreamingAllowed) {
                    onClick()
                }
            }
    )
}
