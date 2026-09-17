package de.lwp2070809.speculonic.ui.components.songdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun LocalDbTab(
    songEntity: SongEntity?,
    sha1: String?,
    onAlbumClick: ((String) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (songEntity == null) {
            Text(stringResource(R.string.loading_local_data))
            return@Column
        }
        DetailItem(stringResource(R.string.title), songEntity.title)

        val artistId = songEntity.artistId
        val hasArtistId = !artistId.isNullOrBlank()
        DetailItem(
            label = stringResource(R.string.artist),
            value = songEntity.artist ?: "-",
            onClick = if (hasArtistId && onArtistClick != null) { { onArtistClick(artistId) } } else null
        )

        val albumId = songEntity.albumId
        val hasAlbumId = !albumId.isNullOrBlank()
        DetailItem(
            label = stringResource(R.string.album),
            value = songEntity.album ?: "-",
            onClick = if (hasAlbumId && onAlbumClick != null) { { onAlbumClick(albumId) } } else null
        )

        DetailItem(stringResource(R.string.track), songEntity.track?.toString() ?: "-")
        DetailItem(stringResource(R.string.year), songEntity.year?.toString() ?: "-")
        DetailItem(stringResource(R.string.genre), songEntity.genre ?: "-")
        DetailItem(stringResource(R.string.duration), FormatUtils.formatDuration(songEntity.duration ?: 0))
        DetailItem(stringResource(R.string.file_format), songEntity.suffix?.uppercase() ?: "-")
        DetailItem(stringResource(R.string.bitrate), songEntity.bitRate?.let { "$it kbps" } ?: "-")
        DetailItem(stringResource(R.string.file_size), FormatUtils.formatSize(songEntity.size ?: 0))
        DetailItem(stringResource(R.string.path), songEntity.path ?: "-")
        DetailItem(stringResource(R.string.content_type), songEntity.contentType ?: "-")
        DetailItem(stringResource(R.string.starred_status), songEntity.starred.toString())

        DetailItem(stringResource(R.string.id), songEntity.id)
        DetailItem(stringResource(R.string.artist_id), songEntity.artistId ?: "-")
        DetailItem(stringResource(R.string.album_id), songEntity.albumId ?: "-")

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        val displayUri = songEntity.localUri?.let { FormatUtils.getFullPhysicalPath(it) }
            ?: stringResource(R.string.not_available_not_cached)
        DetailItem(stringResource(R.string.physical_path), displayUri)
        DetailItem(
            stringResource(R.string.sha_1),
            sha1 ?: if (songEntity.localUri != null) stringResource(R.string.calculating) else stringResource(R.string.not_available_not_cached)
        )
    }
}

@Composable
fun Id3Tab(id3Metadata: Map<String, String>?, isCached: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isCached) {
            Text(
                text = stringResource(R.string.metadata_not_cached_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        if (id3Metadata == null) {
            Text(stringResource(R.string.reading_metadata))
            return@Column
        }
        if (id3Metadata.isEmpty()) {
            Text(stringResource(R.string.no_metadata_found))
            return@Column
        }
        id3Metadata.filterKeys { it != "__FORMAT_INFO__" }.forEach { (key, value) ->
            if (key.startsWith("Lyrics")) {
                Column {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                DetailItem(key, value)
            }
        }

        val formatInfo = id3Metadata["__FORMAT_INFO__"]
        if (formatInfo != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "${stringResource(R.string.container_format_and_version)}: $formatInfo",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RemoteTab(
    localSong: SongEntity?,
    remoteSong: Song?,
    isLoading: Boolean,
    error: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        if (error != null) {
            val displayError = if (error == "OFFLINE") {
                stringResource(R.string.cloud_compare_offline_error)
            } else {
                error
            }
            Text(text = displayError, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (remoteSong == null || localSong == null) {
            Text(stringResource(R.string.no_remote_data))
            return@Column
        }

        val context = LocalContext.current
        val diffs = remember(localSong, remoteSong) { compareSongs(context, localSong, remoteSong) }
        if (diffs.isEmpty()) {
            Text(
                text = stringResource(R.string.data_synchronized),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = stringResource(R.string.differences_found),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            diffs.forEach { diff ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(diff.field, style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.Red.copy(alpha = 0.1f))
                                .padding(4.dp)
                        ) {
                            Text(stringResource(R.string.local_label), style = MaterialTheme.typography.labelSmall)
                            Text(diff.localValue, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.Green.copy(alpha = 0.1f))
                                .padding(4.dp)
                        ) {
                            Text(stringResource(R.string.remote_label), style = MaterialTheme.typography.labelSmall)
                            Text(diff.remoteValue, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

data class DiffItem(val field: String, val localValue: String, val remoteValue: String)

fun compareSongs(
    context: Context,
    local: SongEntity,
    remote: Song
): List<DiffItem> {
    val diffs = mutableListOf<DiffItem>()
    fun check(field: String, l: Any?, r: Any?) {
        val ls = l?.toString() ?: "-"
        val rs = r?.toString() ?: "-"
        if (ls != rs) diffs.add(DiffItem(field, ls, rs))
    }

    check(context.getString(R.string.title), local.title, remote.title)
    check(context.getString(R.string.artist), local.artist, remote.artist)
    check(context.getString(R.string.album), local.album, remote.album)
    check(context.getString(R.string.duration), local.duration, remote.duration)
    check(context.getString(R.string.suffix_label), local.suffix, remote.suffix)
    check(context.getString(R.string.bitrate), local.bitRate, remote.bitRate)
    check(context.getString(R.string.file_size), local.size, remote.size)
    check(context.getString(R.string.path), local.path, remote.path)

    return diffs
}

@Composable
fun DetailItem(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    val isClickable = onClick != null
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (isClickable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None,
            modifier = if (isClickable) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
    }
}
