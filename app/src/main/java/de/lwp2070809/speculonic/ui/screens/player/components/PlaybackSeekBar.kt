package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
