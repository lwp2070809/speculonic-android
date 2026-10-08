package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import de.lwp2070809.speculonic.playback.PlaybackController

@Composable
fun PlaybackQueuePane(
    playbackController: PlaybackController,
    onBackClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playbackState by playbackController.playbackState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        QueueView(
            queue = playbackState.currentQueue,
            currentIndex = playbackState.currentIndex,
            onItemClick = { index -> playbackController.skipToQueueItem(index) },
            onRemoveItem = { index -> playbackController.removeFromQueue(index) },
            modifier = Modifier.fillMaxSize(),
            showTitle = false
        )
    }
}
