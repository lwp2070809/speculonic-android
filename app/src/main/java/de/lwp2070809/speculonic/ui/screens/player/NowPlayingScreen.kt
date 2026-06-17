package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.playback.SleepTimerMode
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.FormatUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    viewModel: NowPlayingViewModel,
    isExpanded: Boolean = false,
    isEffectivelyOnline: Boolean,
    onCollapse: () -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    val playbackState by playbackController.playbackState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    
    var showLyricsMobile by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sleepTimerSheetState = rememberModalBottomSheetState()

    var showSongInfo by remember { mutableStateOf(false) }

    LaunchedEffect(playbackState.currentSongId, playbackState.currentSongTitle, playbackState.currentArtist) {
        if (playbackState.currentSongId.isNotEmpty()) {
            viewModel.loadLyrics(
                artist = playbackState.currentArtist,
                title = playbackState.currentSongTitle,
                songId = playbackState.currentSongId
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isExpanded) {
            NowPlayingExpanded(
                playbackState = playbackState,
                uiState = uiState,
                playbackController = playbackController,
                viewModel = viewModel,
                isEffectivelyOnline = isEffectivelyOnline,
                onCollapse = onCollapse,
                onShowQueue = { showQueue = true },
                onShowSleepTimer = { showSleepTimerSheet = true },
                onShowSongInfo = { showSongInfo = true }
            )
        } else {
            NowPlayingMobile(
                playbackState = playbackState,
                uiState = uiState,
                playbackController = playbackController,
                viewModel = viewModel,
                showLyrics = showLyricsMobile,
                onToggleLyrics = { showLyricsMobile = !showLyricsMobile },
                isEffectivelyOnline = isEffectivelyOnline,
                onCollapse = onCollapse,
                onShowQueue = { showQueue = true },
                onShowSleepTimer = { showSleepTimerSheet = true },
                onShowSongInfo = { showSongInfo = true }
            )
        }
    }

    
    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = queueSheetState
        ) {
            QueueView(
                queue = playbackState.currentQueue,
                currentIndex = playbackState.currentIndex,
                onItemClick = { 
                    playbackController.skipToQueueItem(it)
                    showQueue = false
                },
                onRemoveItem = { playbackController.removeFromQueue(it) }
            )
        }
    }

    
    if (showSleepTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimerSheet = false },
            sheetState = sleepTimerSheetState
        ) {
            SleepTimerOptionsView(
                currentMode = playbackState.sleepTimerMode,
                lastMinutes = uiState.lastSleepTimerMinutes,
                lastSongCount = uiState.lastSleepTimerSongCount,
                onModeSelected = { mode, minutes, songCount ->
                    playbackController.setSleepTimer(mode, minutes, songCount)
                    if (mode == SleepTimerMode.TIME) {
                        viewModel.updateSleepTimerPreferences(minutes = minutes)
                    } else if (mode == SleepTimerMode.SONG_COUNT) {
                        viewModel.updateSleepTimerPreferences(songCount = songCount)
                    }
                    showSleepTimerSheet = false
                },
                onCancelTimer = {
                    playbackController.cancelSleepTimer()
                    showSleepTimerSheet = false
                }
            )
        }
    }

    uiState.currentSong?.let { song ->
        if (showSongInfo) {
            de.lwp2070809.speculonic.ui.components.SongDetailDialog(
                song = song,
                onDismiss = { showSongInfo = false }
            )
        }
    }
}

@Composable
private fun NowPlayingMobile(
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

    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = if (isDragging) tween(0) else spring(),
        label = "dragOffset"
    )

    val collapseThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() }

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

@Composable
private fun NowPlayingExpanded(
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

@Composable
private fun ArtworkView(
    artworkId: String?,
    artworkUri: android.net.Uri?,
    repository: de.lwp2070809.speculonic.domain.repository.SubsonicRepository
) {
    if (artworkUri != null || artworkId != null) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var activeSlot by remember { mutableIntStateOf(0) }
        var model0 by remember { mutableStateOf<Any?>(null) }
        var model1 by remember { mutableStateOf<Any?>(null) }

        val alpha0 = remember { Animatable(1f) }
        val alpha1 = remember { Animatable(0f) }

        LaunchedEffect(artworkId, artworkUri) {
            if (artworkId == null && artworkUri == null) return@LaunchedEffect

            val newModel = repository.buildCoverArtRequest(
                id = artworkId,
                context = context,
                preferLocal = true,
                crossfade = false
            )

            if (model0 == newModel) {
                activeSlot = 0
                if (alpha0.value < 1f) {
                    coroutineScope.launch {
                        launch { alpha0.animateTo(1f, tween(500, easing = EaseInOut)) }
                        launch { alpha1.animateTo(0f, tween(500, easing = EaseInOut)) }
                    }
                }
                return@LaunchedEffect
            }
            if (model1 == newModel) {
                activeSlot = 1
                if (alpha1.value < 1f) {
                    coroutineScope.launch {
                        launch { alpha1.animateTo(1f, tween(500, easing = EaseInOut)) }
                        launch { alpha0.animateTo(0f, tween(500, easing = EaseInOut)) }
                    }
                }
                return@LaunchedEffect
            }

            if (model0 == null) {
                model0 = newModel
                coroutineScope.launch { alpha0.snapTo(1f) }
                activeSlot = 0
            } else if (model1 == null) {
                model1 = newModel
                coroutineScope.launch { alpha1.snapTo(0f) }
                activeSlot = 1
            } else {
                if (alpha0.value >= alpha1.value) {
                    model1 = newModel
                    coroutineScope.launch {
                        alpha1.snapTo(0f)
                        alpha0.animateTo(1f, tween(300))
                    }
                    activeSlot = 1
                } else {
                    model0 = newModel
                    coroutineScope.launch {
                        alpha0.snapTo(0f)
                        alpha1.animateTo(1f, tween(300))
                    }
                    activeSlot = 0
                }
            }
        }

        val startTransition = { targetSlot: Int ->
            coroutineScope.launch {
                if (targetSlot == 0 && activeSlot == 0) {
                    launch { alpha0.animateTo(1f, tween(500, easing = EaseInOut)) }
                    launch { alpha1.animateTo(0f, tween(500, easing = EaseInOut)) }
                } else if (targetSlot == 1 && activeSlot == 1) {
                    launch { alpha1.animateTo(1f, tween(500, easing = EaseInOut)) }
                    launch { alpha0.animateTo(0f, tween(500, easing = EaseInOut)) }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (model0 != null) {
                AsyncImage(
                    model = model0,
                    contentDescription = null,
                    onSuccess = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                    onError = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alpha0.value },
                    contentScale = ContentScale.Crop
                )
            }
            if (model1 != null) {
                AsyncImage(
                    model = model1,
                    contentDescription = null,
                    onSuccess = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                    onError = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alpha1.value },
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SongInfo(playbackState: PlaybackState, modifier: Modifier = Modifier) {
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
private fun PlaybackSeekBar(
    playbackState: PlaybackState,
    playbackController: PlaybackController
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {},
                    onVerticalDrag = { change, _ -> change.consume() },
                    onDragEnd = {},
                    onDragCancel = {}
                )
            }
    ) {
        var isSliderDragging by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var sliderDragValue by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(0f) }
        
        val currentSliderValue = if (isSliderDragging) sliderDragValue else if (playbackState.duration > 0) {
            playbackState.currentPosition.toFloat() / playbackState.duration.toFloat()
        } else 0f
        
        Slider(
            value = currentSliderValue,
            onValueChange = { 
                isSliderDragging = true
                sliderDragValue = it
            },
            onValueChangeFinished = {
                if (isSliderDragging) {
                    if (playbackState.duration > 0) {
                        playbackController.seekTo((sliderDragValue * playbackState.duration).toLong())
                    }
                }
                isSliderDragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val displayPosition = if (isSliderDragging && playbackState.duration > 0) {
                (sliderDragValue * playbackState.duration).toLong()
            } else playbackState.currentPosition
            
            Text(FormatUtils.formatDuration(displayPosition), style = MaterialTheme.typography.labelMedium)
            Text(FormatUtils.formatDuration(playbackState.duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MainControls(
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
                playbackState.shuffleModeEnabled -> Icons.Default.Shuffle
                playbackState.repeatMode == Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
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
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            
            FloatingActionButton(
                onClick = { playbackController.togglePlayPause() },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }

            IconButton(onClick = { playbackController.skipToNext() }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }
        }

        
        IconButton(
            onClick = { onShowSongInfo() }
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.song_info),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ExtraControls(
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
                imageVector = Icons.Default.Timer, 
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


@Composable
private fun PlayerBlurBackground(
    artworkId: String?,
    artworkUri: android.net.Uri?,
    playerBackgroundMode: PlayerBackgroundMode,
    modifier: Modifier = Modifier
) {
    if (playerBackgroundMode != PlayerBackgroundMode.GAUSSIAN_BLUR) return

    val repository = LocalSubsonicRepository.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var activeSlot by remember { mutableIntStateOf(0) }
    var model0 by remember { mutableStateOf<Any?>(null) }
    var model1 by remember { mutableStateOf<Any?>(null) }

    val alpha0 = remember { Animatable(1f) }
    val alpha1 = remember { Animatable(0f) }

    var debouncedArtworkId by remember { mutableStateOf(artworkId) }
    var debouncedArtworkUri by remember { mutableStateOf(artworkUri) }

    LaunchedEffect(artworkId, artworkUri) {
        kotlinx.coroutines.delay(150) 
        debouncedArtworkId = artworkId
        debouncedArtworkUri = artworkUri
    }

    LaunchedEffect(debouncedArtworkId, debouncedArtworkUri, lifecycleOwner) {
        if (debouncedArtworkId == null && debouncedArtworkUri == null) return@LaunchedEffect

        val isAppVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        val newModel = repository.buildCoverArtRequest(
            id = debouncedArtworkId,
            context = context,
            preferLocal = true,
            size = 300,
            crossfade = false
        )

        if (!isAppVisible) {
            if (alpha0.value >= alpha1.value) {
                model1 = newModel
                alpha1.snapTo(1f)
                alpha0.snapTo(0f)
                activeSlot = 1
            } else {
                model0 = newModel
                alpha0.snapTo(1f)
                alpha1.snapTo(0f)
                activeSlot = 0
            }
            return@LaunchedEffect
        }

        if (model0 == newModel) {
            activeSlot = 0
            if (alpha0.value < 1f) {
                coroutineScope.launch {
                    launch { alpha0.animateTo(1f, tween(800, easing = EaseInOut)) }
                    launch { alpha1.animateTo(0f, tween(800, easing = EaseInOut)) }
                }
            }
            return@LaunchedEffect
        }
        if (model1 == newModel) {
            activeSlot = 1
            if (alpha1.value < 1f) {
                coroutineScope.launch {
                    launch { alpha1.animateTo(1f, tween(800, easing = EaseInOut)) }
                    launch { alpha0.animateTo(0f, tween(800, easing = EaseInOut)) }
                }
            }
            return@LaunchedEffect
        }

        if (model0 == null) {
            model0 = newModel
            coroutineScope.launch { alpha0.snapTo(1f) }
            activeSlot = 0
        } else if (model1 == null) {
            model1 = newModel
            coroutineScope.launch { alpha1.snapTo(0f) }
            activeSlot = 1
        } else {
            if (alpha0.value >= alpha1.value) {
                model1 = newModel
                coroutineScope.launch {
                    alpha1.snapTo(0f)
                    alpha0.animateTo(1f, tween(300))
                }
                activeSlot = 1
            } else {
                model0 = newModel
                coroutineScope.launch {
                    alpha0.snapTo(0f)
                    alpha1.animateTo(1f, tween(300))
                }
                activeSlot = 0
            }
        }
    }

    val startTransition = { targetSlot: Int ->
        coroutineScope.launch {
            if (targetSlot == 0 && activeSlot == 0) {
                launch { alpha0.animateTo(1f, tween(800, easing = EaseInOut)) }
                launch { alpha1.animateTo(0f, tween(800, easing = EaseInOut)) }
            } else if (targetSlot == 1 && activeSlot == 1) {
                launch { alpha1.animateTo(1f, tween(800, easing = EaseInOut)) }
                launch { alpha0.animateTo(0f, tween(800, easing = EaseInOut)) }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (model0 != null) {
            AsyncImage(
                model = model0,
                contentDescription = null,
                onSuccess = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                onError = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f * alpha0.value }
                    .blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Crop
            )
        }
        if (model1 != null) {
            AsyncImage(
                model = model1,
                contentDescription = null,
                onSuccess = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                onError = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f * alpha1.value }
                    .blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Crop
            )
        }
    }
}
