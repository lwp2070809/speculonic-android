package de.lwp2070809.speculonic.ui.screens.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    internalBytes: Long,
    maxCacheBytes: Long,
    cachedSongsCount: Int,
    songsCount: Int,
    playbackSizeLabel: String,
    coverArtSizeLabel: String,
    songSizeLabel: String,
    otherSizeLabel: String,
    onClearCacheClick: () -> Unit,
    onClearPlaybackClick: () -> Unit,
    onClearCoverArtClick: () -> Unit,
    onClearSongsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        )
    )

    val isOverQuota = remember(maxCacheBytes, internalBytes) {
        maxCacheBytes != -1L && internalBytes > maxCacheBytes
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
            .clickable { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cache_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isOverQuota) {
                        IconButton(
                            onClick = {
                                onClearCacheClick()
                            },
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
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "展开详情",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            val total = maxCacheBytes.coerceAtLeast(1L).toFloat()
            val progressWeight = if (maxCacheBytes == -1L) 1.0f else (internalBytes.toFloat() / total).coerceAtMost(1f)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    if (expanded) {
                        val playbackWeight = if (internalBytes > 0) (playbackBytes.toFloat() / internalBytes) * progressWeight else 0f
                        val coverArtWeight = if (internalBytes > 0) (coverArtBytes.toFloat() / internalBytes) * progressWeight else 0f
                        val songWeight = if (internalBytes > 0) (songBytes.toFloat() / internalBytes) * progressWeight else 0f
                        val otherWeight = if (internalBytes > 0) (otherBytes.toFloat() / internalBytes) * progressWeight else 0f

                        if (songWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(songWeight)
                                    .fillMaxSize()
                                    .background(Color(0xFF2196F3))
                            )
                        }
                        if (coverArtWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(coverArtWeight)
                                    .fillMaxSize()
                                    .background(Color(0xFF4CAF50))
                            )
                        }
                        if (playbackWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(playbackWeight)
                                    .fillMaxSize()
                                    .background(Color(0xFFFF9800))
                            )
                        }
                        if (otherWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(otherWeight)
                                    .fillMaxSize()
                                    .background(Color(0xFF9C27B0))
                            )
                        }
                        if (1f - progressWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f - progressWeight)
                                    .fillMaxSize()
                            )
                        }
                    } else {
                        val progressColor = if (isOverQuota) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        if (progressWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(progressWeight)
                                    .fillMaxSize()
                                    .background(progressColor)
                            )
                        }
                        if (1f - progressWeight > 0.001f) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f - progressWeight)
                                    .fillMaxSize()
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    LegendItem(
                        label = stringResource(R.string.internal_cache_color_label),
                        size = if (maxCacheBytes == -1L) FormatUtils.formatSize(internalBytes) else "${FormatUtils.formatSize(internalBytes)} / ${FormatUtils.formatSize(maxCacheBytes)}",
                        color = if (isOverQuota) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                    
                    CacheDetailRow(
                        label = stringResource(R.string.song_cache_color_label),
                        size = songSizeLabel,
                        color = Color(0xFF2196F3),
                        onClearClick = onClearSongsClick
                    )

                    CacheDetailRow(
                        label = stringResource(R.string.cover_art_cache_color_label),
                        size = coverArtSizeLabel,
                        color = Color(0xFF4CAF50),
                        onClearClick = onClearCoverArtClick
                    )

                    CacheDetailRow(
                        label = stringResource(R.string.playback_cache_color_label),
                        size = playbackSizeLabel,
                        color = Color(0xFFFF9800),
                        onClearClick = onClearPlaybackClick
                    )

                    CacheDetailRow(
                        label = stringResource(R.string.other_cache_color_label),
                        size = otherSizeLabel,
                        color = Color(0xFF9C27B0),
                        onClearClick = null
                    )
                }
            }

            Text(
                text = stringResource(R.string.internal_cache_description),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )

            if (isOverQuota) {
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
                        text = stringResource(R.string.cache_quota_exceeded),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val ratio = if (songsCount == 0) 0 else (cachedSongsCount * 100) / songsCount
                Column {
                    Text(
                        text = stringResource(R.string.cached_songs_ratio),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "$ratio% (共 $cachedSongsCount/$songsCount 首)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                CircularProgressIndicator(
                    progress = { ratio.toFloat() / 100f },
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
fun LegendItem(
    label: String,
    size: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
            Text(
                text = size,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun CacheDetailRow(
    label: String,
    size: String,
    color: androidx.compose.ui.graphics.Color,
    onClearClick: (() -> Unit)?
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
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = size,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (onClearClick != null) {
                IconButton(
                    onClick = onClearClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
        }
    }
}
