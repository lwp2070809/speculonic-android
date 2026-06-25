package de.lwp2070809.speculonic.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun StorageDashboardCard(
    playbackBytes: Long,
    coverArtBytes: Long,
    songBytes: Long,
    otherBytes: Long,
    totalSpaceBytes: Long,
    maxCoverBytes: Long,
    isCoverOverQuota: Boolean,
    playbackSizeLabel: String,
    coverArtSizeLabel: String,
    songSizeLabel: String,
    otherSizeLabel: String,
    onClearPlaybackClick: () -> Unit,
    onClearCoverArtClick: () -> Unit,
    onClearSongsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnlimited = maxCoverBytes == -1L || maxCoverBytes == 5L * 1024 * 1024 * 1024
    val coverProgress = if (isUnlimited) {
        1.0f
    } else {
        (coverArtBytes.toFloat() / maxCoverBytes.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_cache_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isCoverOverQuota) {
                    IconButton(
                        onClick = onClearCoverArtClick,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                shape = CircleShape
                            )
                            .size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = stringResource(R.string.clear_cache_quota_warning),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 1. 专辑封面
            val coverLimitLabel = if (isUnlimited) {
                coverArtSizeLabel
            } else {
                "$coverArtSizeLabel / ${FormatUtils.formatSize(maxCoverBytes)}"
            }
            StorageItemRow(
                label = stringResource(R.string.cover_art_cache_color_label),
                sizeLabel = coverLimitLabel,
                progress = coverProgress,
                color = if (isCoverOverQuota) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                onClearClick = onClearCoverArtClick
            )

            // 2. 播放缓冲
            val playbackMax = 1024L * 1024 * 1024 // 1 GB
            val playbackProgress = (playbackBytes.toFloat() / playbackMax.toFloat()).coerceIn(0f, 1f)
            StorageItemRow(
                label = stringResource(R.string.playback_cache_color_label),
                sizeLabel = "$playbackSizeLabel / 1 GB",
                progress = playbackProgress,
                color = Color(0xFFFF9800),
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                onClearClick = onClearPlaybackClick
            )

            // 3. 已缓存歌曲
            val songTotal = totalSpaceBytes
            val songProgress = if (songTotal > 0) (songBytes.toFloat() / songTotal.toFloat()).coerceIn(0f, 1f) else 0f
            StorageItemRow(
                label = stringResource(R.string.song_cache_color_label),
                sizeLabel = "$songSizeLabel / ${FormatUtils.formatSize(totalSpaceBytes)}",
                progress = songProgress,
                color = Color(0xFF2196F3),
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                onClearClick = onClearSongsClick
            )

            // 4. 其他缓存 (无进度条)
            StorageItemRow(
                label = stringResource(R.string.other_cache_color_label),
                sizeLabel = otherSizeLabel,
                progress = null,
                color = Color(0xFF9C27B0),
                trackColor = Color.Transparent,
                onClearClick = null
            )

            if (isCoverOverQuota) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.cover_quota_exceeded),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun StorageItemRow(
    label: String,
    sizeLabel: String,
    progress: Float?,
    color: Color,
    trackColor: Color,
    onClearClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (onClearClick != null) {
                    IconButton(
                        onClick = onClearClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(24.dp))
                }
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = trackColor
            )
        }
    }
}
