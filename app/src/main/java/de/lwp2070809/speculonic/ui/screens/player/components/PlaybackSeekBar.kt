package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackState
import de.lwp2070809.speculonic.util.FormatUtils
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PlaybackSeekBar(
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
        var isSliderDragging by rememberSaveable { mutableStateOf(false) }
        var sliderDragValue by rememberSaveable { mutableFloatStateOf(0f) }

        PlaybackProgressSlider(
            currentPositionFlow = playbackController.currentPosition,
            durationMs = playbackState.duration,
            isDragging = isSliderDragging,
            dragValue = sliderDragValue,
            onValueChange = {
                isSliderDragging = true
                sliderDragValue = it
            },
            onValueChangeFinished = {
                if (isSliderDragging && playbackState.duration > 0) {
                    playbackController.seekTo((sliderDragValue * playbackState.duration).toLong())
                }
                isSliderDragging = false
            }
        )

        val formattedDuration = remember(playbackState.duration) {
            FormatUtils.formatDuration(playbackState.duration)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PlaybackPositionText(
                currentPositionFlow = playbackController.currentPosition,
                durationMs = playbackState.duration,
                isDragging = isSliderDragging,
                dragValue = sliderDragValue
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaybackProgressSlider(
    currentPositionFlow: StateFlow<Long>,
    durationMs: Long,
    isDragging: Boolean,
    dragValue: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val currentSliderValue = if (isDragging) {
        dragValue
    } else if (durationMs > 0) {
        (currentPosition.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val sliderState = remember { SliderState() }
    sliderState.value = currentSliderValue
    Slider(
        state = sliderState,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun PlaybackPositionText(
    currentPositionFlow: StateFlow<Long>,
    durationMs: Long,
    isDragging: Boolean,
    dragValue: Float
) {
    val currentPositionState = currentPositionFlow.collectAsStateWithLifecycle()
    val displaySeconds by remember(currentPositionFlow, isDragging, dragValue, durationMs) {
        derivedStateOf {
            calculateDisplayPositionSeconds(
                currentPositionMs = currentPositionState.value,
                durationMs = durationMs,
                isDragging = isDragging,
                dragValue = dragValue
            )
        }
    }
    val formattedPosition = remember(displaySeconds) {
        FormatUtils.formatDuration(displaySeconds * 1000L)
    }

    Text(
        text = formattedPosition,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun calculateDisplayPositionSeconds(
    currentPositionMs: Long,
    durationMs: Long,
    isDragging: Boolean,
    dragValue: Float
): Long {
    val pos = if (isDragging && durationMs > 0) {
        (dragValue.coerceIn(0f, 1f) * durationMs).toLong()
    } else {
        currentPositionMs
    }
    return pos.coerceAtLeast(0L) / 1000L
}
