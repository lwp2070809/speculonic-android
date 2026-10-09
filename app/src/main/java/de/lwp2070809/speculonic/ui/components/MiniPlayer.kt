package de.lwp2070809.speculonic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.buildRequest

@Composable
fun MiniPlayer(
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFloating: Boolean = false
) {
    val coverRequester = LocalCoverArtRequester.current
    val playbackController = LocalPlaybackController.current
    
    val playbackStateState = playbackController.playbackState.collectAsState()
    val artworkId by remember { derivedStateOf { playbackStateState.value.artworkId } }
    val artworkUri by remember { derivedStateOf { playbackStateState.value.artworkUri } }
    val currentSongTitle by remember { derivedStateOf { playbackStateState.value.currentSongTitle } }
    val currentArtist by remember { derivedStateOf { playbackStateState.value.currentArtist } }
    val isPlaying by remember { derivedStateOf { playbackStateState.value.isPlaying } }

    val shape = if (isFloating) RoundedCornerShape(16.dp) else RoundedCornerShape(0.dp)
    val tonalElevation = if (isFloating) 8.dp else 2.dp
    val border = if (isFloating) {
        androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    } else null
    
    val floatingModifier = if (isFloating) {
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.15f),
                spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)
            )
    } else {
        Modifier.fillMaxWidth()
    }
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = shape,
        shadowElevation = 0.dp,
        tonalElevation = tonalElevation,
        border = border,
        modifier = modifier
            .then(floatingModifier)
            .height(68.dp)
            .clip(shape)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                
                if (artworkUri != null || artworkId != null) {
                    val model = remember(artworkId, artworkUri) {
                        coverRequester.buildRequest(
                            id = artworkId,
                            preferLocal = true
                        )
                    }
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSongTitle.ifEmpty {
                            stringResource(R.string.not_playing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentArtist.ifEmpty {
                            stringResource(R.string.not_playing_artist)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSkipPrevious) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_skip_previous),
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onPlayPause) {
                        Icon(
                            painter = if (isPlaying) androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_pause) else rememberVectorPainter(Icons.Default.PlayArrow),
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_skip_next),
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            MiniPlayerProgressBar(playbackController)
        }
    }
}

@Composable
private fun MiniPlayerProgressBar(playbackController: de.lwp2070809.speculonic.playback.PlaybackController) {
    val playbackState by playbackController.playbackState.collectAsState()
    val progress = if (playbackState.duration > 0) {
        playbackState.currentPosition.toFloat() / playbackState.duration.toFloat()
    } else 0f
    
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
    )
}
