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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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

/**
 * 歌曲列表项高频文本资源包装类，避免大列表快速滚动时子项内部重复查询 CompositionLocal。
 */
@Immutable
data class SongListItemStrings(
    val successMsg: String,
    val existsMsg: String,
    val errorMsg: String,
    val cdDownloaded: String,
    val cdStar: String,
    val moreOptions: String,
    val download: String,
    val removeDownload: String,
    val songDetails: String,
    val addToPlaylist: String,
    val removeFromPlaylist: String,
    val incompatibleDownloadedTemplate: String,
    val unsupportedFormatTemplate: String
) {
    fun formatIncompatibleDownloaded(suffix: String): String =
        String.format(incompatibleDownloadedTemplate, suffix)

    fun formatUnsupportedFormat(suffix: String): String =
        String.format(unsupportedFormatTemplate, suffix)
}

/**
 * 在列表父级作用域中缓存并记忆常用的歌曲列表项文本，实现整表复用。
 */
@Composable
fun rememberSongListItemStrings(): SongListItemStrings {
    val successMsg = stringResource(R.string.add_to_playlist_success)
    val existsMsg = stringResource(R.string.song_already_in_playlist)
    val errorMsg = stringResource(R.string.add_to_playlist_error)
    val cdDownloaded = stringResource(R.string.cd_downloaded)
    val cdStar = stringResource(R.string.cd_star)
    val moreOptions = stringResource(R.string.more_options)
    val download = stringResource(R.string.download)
    val removeDownload = stringResource(R.string.remove_download)
    val songDetails = stringResource(R.string.song_details)
    val addToPlaylist = stringResource(R.string.add_to_playlist)
    val removeFromPlaylist = stringResource(R.string.remove_from_playlist)
    val context = LocalContext.current
    val incompatibleDownloadedTemplate = remember(context) {
        context.getString(R.string.incompatible_downloaded_hint)
    }
    val unsupportedFormatTemplate = remember(context) {
        context.getString(R.string.unsupported_format_hint)
    }

    return remember(
        successMsg,
        existsMsg,
        errorMsg,
        cdDownloaded,
        cdStar,
        moreOptions,
        download,
        removeDownload,
        songDetails,
        addToPlaylist,
        removeFromPlaylist,
        incompatibleDownloadedTemplate,
        unsupportedFormatTemplate
    ) {
        SongListItemStrings(
            successMsg = successMsg,
            existsMsg = existsMsg,
            errorMsg = errorMsg,
            cdDownloaded = cdDownloaded,
            cdStar = cdStar,
            moreOptions = moreOptions,
            download = download,
            removeDownload = removeDownload,
            songDetails = songDetails,
            addToPlaylist = addToPlaylist,
            removeFromPlaylist = removeFromPlaylist,
            incompatibleDownloadedTemplate = incompatibleDownloadedTemplate,
            unsupportedFormatTemplate = unsupportedFormatTemplate
        )
    }
}

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
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    isRemoveFromPlaylistEnabled: Boolean = true,
    trailingContentOverride: @Composable (() -> Unit)? = null,
    strings: SongListItemStrings = rememberSongListItemStrings()
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

    if (showDetailDialog) {
        SongDetailDialog(song = song, onDismiss = { showDetailDialog = false })
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            song = song,
            onDismiss = { showAddToPlaylistDialog = false },
            onResult = { result ->
                val message = when (result) {
                    PlaylistAddResult.SUCCESS -> strings.successMsg
                    PlaylistAddResult.ALREADY_EXISTS -> strings.existsMsg
                    PlaylistAddResult.ERROR -> strings.errorMsg
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
                        contentDescription = strings.cdDownloaded,
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
                            contentDescription = strings.cdStar,
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = strings.moreOptions)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (isDownloaded) {
                                DropdownMenuItem(
                                    text = { Text(strings.removeDownload) },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_delete), contentDescription = null) },
                                    onClick = { 
                                        onRemoveDownloadClick()
                                        showMenu = false
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(strings.download) },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_download), contentDescription = null) },
                                    onClick = { 
                                        onDownloadClick()
                                        showMenu = false
                                    },
                                    enabled = isStreamingAllowed
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(strings.songDetails) },
                                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_info), contentDescription = null) },
                                onClick = { 
                                    showDetailDialog = true
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.addToPlaylist) },
                                leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_playlist_add), contentDescription = null) },
                                onClick = { 
                                    showAddToPlaylistDialog = true
                                    showMenu = false
                                },
                                enabled = isEffectivelyOnline
                            )
                            if (onRemoveFromPlaylistClick != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(strings.removeFromPlaylist) },
                                    leadingIcon = { Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_remove_circle_outline), contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onRemoveFromPlaylistClick()
                                    },
                                    enabled = isRemoveFromPlaylistEnabled
                                )
                            }
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
                        strings.formatIncompatibleDownloaded(suffixText)
                    } else {
                        strings.formatUnsupportedFormat(suffixText)
                    }
                    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                } else if (isDownloaded || isStreamingAllowed) {
                    onClick()
                }
            }
    )
}
