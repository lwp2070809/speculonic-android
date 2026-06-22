package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

@Composable
fun NowPlayingMobile(
    playbackState: PlaybackState,
    uiState: NowPlayingUiState,
    playbackController: PlaybackController,
    viewModel: NowPlayingViewModel,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
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
                primaryColor.copy(alpha = 0.18f),
                primaryColor.copy(alpha = 0.06f),
                surfaceColor
            )
        )
    }
    val scrimBrush = remember(surfaceColor) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.3f),
                surfaceColor.copy(alpha = 0.7f),
                surfaceColor
            )
        )
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (isDragging) tween(0) else spring(),
        label = "dragOffset"
    )

    val collapseThresholdPx = with(LocalDensity.current) { 150.dp.toPx() }

    val dragModifier = Modifier.pointerInput(showLyrics) {
        detectVerticalDragGestures(
            onDragStart = { isDragging = true },
            onVerticalDrag = { change, dragAmount ->
                dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                change.consume()
            },
            onDragEnd = {
                isDragging = false
                if (dragOffset > collapseThresholdPx) {
                    onCollapse()
                    dragOffset = 0f
                } else {
                    dragOffset = 0f
                }
            },
            onDragCancel = {
                isDragging = false
                dragOffset = 0f
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, animatedOffset.roundToInt()) }
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

        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding() 
        ) {
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .then(dragModifier),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp, 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .clickable { onCollapse() } 
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().then(dragModifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                Icons.Default.KeyboardArrowDown, 
                                contentDescription = "Collapse",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = playbackState.queueTitle ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = onShowQueue) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistPlay, 
                                contentDescription = "Queue",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    
                    val lyricsAlpha by animateFloatAsState(
                        targetValue = if (showLyrics) 1f else 0f,
                        animationSpec = tween(durationMillis = 250),
                        label = "LyricsAlpha"
                    )
                    val artworkAlpha by animateFloatAsState(
                        targetValue = if (showLyrics) 0f else 1f,
                        animationSpec = tween(durationMillis = 250),
                        label = "ArtworkAlpha"
                    )
                    val lyricsScale by animateFloatAsState(
                        targetValue = if (showLyrics) 1f else 0.92f,
                        animationSpec = tween(durationMillis = 250),
                        label = "LyricsScale"
                    )
                    val artworkScale by animateFloatAsState(
                        targetValue = if (showLyrics) 1f else 1.05f,
                        animationSpec = tween(durationMillis = 250),
                        label = "ArtworkScale"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .then(if (!showLyrics) dragModifier else Modifier)
                            .clickable { onToggleLyrics() }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = artworkAlpha
                                    scaleX = artworkScale
                                    scaleY = artworkScale
                                }
                        ) {
                            ArtworkView(
                                artworkId = playbackState.artworkId,
                                artworkUri = playbackState.artworkUri,
                                repository = repository
                            )
                        }
                        if (lyricsAlpha > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = lyricsAlpha
                                        scaleX = lyricsScale
                                        scaleY = lyricsScale
                                    }
                            ) {
                                if (uiState.isLoadingLyrics) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else {
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

                    Spacer(modifier = Modifier.height(24.dp))

                    
                    SongInfo(playbackState, modifier = dragModifier)
                }
            }

            
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .weight(1f)
                    .background(scrimBrush)
                    .then(dragModifier)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    PlaybackSeekBar(playbackState, playbackController)
                    MainControls(playbackState, playbackController, uiState, viewModel, onShowSongInfo)
                    ExtraControls(playbackState, uiState, viewModel, isEffectivelyOnline, onShowSleepTimer)
                }
            }
        }
    }
}
