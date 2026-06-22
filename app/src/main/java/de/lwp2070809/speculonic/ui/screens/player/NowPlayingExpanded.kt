package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.ui.screens.player.components.ArtworkView
import de.lwp2070809.speculonic.ui.screens.player.components.ExtraControls
import de.lwp2070809.speculonic.ui.screens.player.components.MainControls
import de.lwp2070809.speculonic.ui.screens.player.components.PlaybackSeekBar
import de.lwp2070809.speculonic.ui.screens.player.components.PlayerBlurBackground
import de.lwp2070809.speculonic.ui.screens.player.components.SongInfo

@Composable
fun NowPlayingExpanded(
    playbackState: PlaybackState,
    uiState: NowPlayingUiState,
    playbackController: PlaybackController,
    viewModel: NowPlayingViewModel,
    isEffectivelyOnline: Boolean,
    onCollapse: () -> Unit,
    onShowQueue: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowSongInfo: () -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val gradientBrush = remember(primaryColor, surfaceColor) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.15f),
                surfaceColor
            )
        )
    }

    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor) 
    ) {
        
        PlayerBlurBackground(
            artworkId = playbackState.artworkId,
            artworkUri = playbackState.artworkUri,
            playerBackgroundMode = uiState.playerBackgroundMode
        )

        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        )

        
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(32.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                    Text(
                        text = playbackState.queueTitle ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                    )
                    IconButton(onClick = onShowQueue) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = "Queue")
                    }
                }

                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                    ) {
                        ArtworkView(
                            artworkId = playbackState.artworkId,
                            artworkUri = playbackState.artworkUri,
                            repository = repository
                        )
                    }
                }

                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SongInfo(playbackState)
                    PlaybackSeekBar(playbackState, playbackController)
                    MainControls(playbackState, playbackController, uiState, viewModel, onShowSongInfo)
                    ExtraControls(playbackState, uiState, viewModel, isEffectivelyOnline, onShowSleepTimer)
                }
            }

            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(32.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                LyricsView(
                    lyricsLines = uiState.lyricsLines,
                    rawLyrics = uiState.rawLyrics,
                    currentPosition = playbackState.currentPosition,
                    isPlaying = playbackState.isPlaying,
                    isLoading = uiState.isLoadingLyrics,
                    onSeek = { playbackController.seekTo(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
