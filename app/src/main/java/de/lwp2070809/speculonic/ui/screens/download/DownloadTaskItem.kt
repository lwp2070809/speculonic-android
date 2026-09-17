package de.lwp2070809.speculonic.ui.screens.download

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import de.lwp2070809.speculonic.R
import org.json.JSONObject

@OptIn(UnstableApi::class)
@Composable
fun DownloadTaskItem(
    task: Download,
    progress: Float,
    state: Int,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val unknownSong = stringResource(R.string.unknown_song)
    val unknownArtist = stringResource(R.string.unknown_artist)

    val title = remember(task.request.data, unknownSong) {
        try {
            if (task.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(task.request.data))
                json.optString("title", unknownSong)
            } else {
                unknownSong
            }
        } catch (e: Exception) {
            unknownSong
        }
    }

    val artist = remember(task.request.data, unknownArtist) {
        try {
            if (task.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(task.request.data))
                json.optString("artist", unknownArtist)
            } else {
                unknownArtist
            }
        } catch (e: Exception) {
            unknownArtist
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val isSilent = isSilentDownload(task)
                val isSilentBufferingOrQueuing = isSilent && progress == 0f && (state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED)

                val statusLabel = if (isSilentBufferingOrQueuing) {
                    if (state == Download.STATE_QUEUED) stringResource(R.string.silent_queued_status)
                    else stringResource(R.string.silent_downloading_status)
                } else {
                    getDownloadStatusLabel(state, progress)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = getDownloadStatusColor(state)
                    )
                    if (!isSilentBufferingOrQueuing && (state == Download.STATE_DOWNLOADING || state == Download.STATE_STOPPED)) {
                        Text(
                            text = "${progress.toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isSilentBufferingOrQueuing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else if (state == Download.STATE_DOWNLOADING || state == Download.STATE_STOPPED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (state == Download.STATE_STOPPED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (state) {
                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                        IconButton(onClick = onPauseClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_symbol_pause),
                                contentDescription = stringResource(R.string.content_description_pause),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Download.STATE_STOPPED -> {
                        IconButton(onClick = onResumeClick) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.content_description_resume),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                IconButton(onClick = onCancelClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.content_description_cancel_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun getDownloadStatusLabel(state: Int, progress: Float): String {
    return when (state) {
        Download.STATE_QUEUED -> stringResource(R.string.download_state_queued)
        Download.STATE_DOWNLOADING -> stringResource(R.string.download_state_downloading)
        Download.STATE_COMPLETED -> stringResource(R.string.download_state_completed)
        Download.STATE_FAILED -> stringResource(R.string.download_state_failed)
        Download.STATE_STOPPED -> stringResource(R.string.download_state_stopped)
        Download.STATE_REMOVING -> stringResource(R.string.download_state_removing)
        Download.STATE_RESTARTING -> stringResource(R.string.download_state_restarting)
        else -> stringResource(R.string.download_state_unknown)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun getDownloadStatusColor(state: Int): Color {
    return when (state) {
        Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> MaterialTheme.colorScheme.primary
        Download.STATE_COMPLETED -> MaterialTheme.colorScheme.secondary
        Download.STATE_FAILED -> MaterialTheme.colorScheme.error
        Download.STATE_STOPPED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(UnstableApi::class)
fun isSilentDownload(download: Download): Boolean {
    return try {
        if (download.request.data.isNotEmpty()) {
            val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
            json.optBoolean("isSilent", false)
        } else false
    } catch (e: Exception) {
        false
    }
}
