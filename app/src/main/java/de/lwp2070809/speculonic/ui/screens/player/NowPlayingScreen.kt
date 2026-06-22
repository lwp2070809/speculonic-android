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
import de.lwp2070809.speculonic.ui.screens.player.components.*

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
