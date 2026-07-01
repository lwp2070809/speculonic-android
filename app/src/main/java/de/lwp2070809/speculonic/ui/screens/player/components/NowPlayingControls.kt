package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.ui.screens.player.NowPlayingUiState
import de.lwp2070809.speculonic.ui.screens.player.NowPlayingViewModel
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun SongInfo(playbackState: PlaybackState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = playbackState.currentSongTitle.ifEmpty {
                stringResource(R.string.not_playing)
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = playbackState.currentArtist.ifEmpty {
                stringResource(R.string.not_playing_artist)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MainControls(
    playbackState: PlaybackState,
    playbackController: PlaybackController,
    uiState: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    onShowSongInfo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        
        IconButton(
            onClick = { playbackController.togglePlaybackMode() }
        ) {
            val icon = when {
                playbackState.shuffleModeEnabled -> androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_shuffle)
                playbackState.repeatMode == Player.REPEAT_MODE_ONE -> androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_repeat_one)
                else -> androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_repeat)
            }
            Icon(
                icon, 
                contentDescription = stringResource(R.string.playback_mode),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconButton(onClick = { playbackController.skipToPrevious() }) {
                Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_skip_previous), contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            
            FloatingActionButton(
                onClick = { playbackController.togglePlayPause() },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    painter = if (playbackState.isPlaying) androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_pause) else androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.PlayArrow),
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }

            IconButton(onClick = { playbackController.skipToNext() }) {
                Icon(androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_skip_next), contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
        }

        
        IconButton(
            onClick = { onShowSongInfo() }
        ) {
            Icon(
                androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_info),
                contentDescription = stringResource(R.string.song_info),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ExtraControls(
    playbackState: PlaybackState,
    uiState: NowPlayingUiState,
    viewModel: NowPlayingViewModel,
    isEffectivelyOnline: Boolean,
    onShowSleepTimer: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TextButton(
            onClick = { viewModel.toggleFavorite() },
            enabled = isEffectivelyOnline,
            modifier = Modifier.alpha(if (isEffectivelyOnline) 1.0f else 0.38f)
        ) {
            Icon(
                if (uiState.isStarred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                null, 
                modifier = Modifier.size(20.dp),
                tint = if (uiState.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.favorite),
                color = if (uiState.isStarred) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        }
        TextButton(onClick = onShowSleepTimer) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_timer), 
                contentDescription = null, 
                modifier = Modifier.size(20.dp),
                tint = if (playbackState.isSleepTimerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (playbackState.isSleepTimerRunning) {
                    FormatUtils.formatDuration(playbackState.sleepTimerRemainingMillis)
                } else {
                    stringResource(R.string.sleep_timer)
                },
                color = if (playbackState.isSleepTimerRunning) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        }
    }
}
