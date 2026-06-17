package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ActionButtonsRow(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    isDownloading: Boolean,
    isDownloadEnabled: Boolean = true
) {
    var showDownloadAllDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showDownloadAllDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadAllDialog = false },
            title = { Text(stringResource(R.string.confirm_download_all_title)) },
            text = { Text(stringResource(R.string.confirm_download_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadAllDialog = false
                    onDownloadAll()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPlayAll,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.play_all), maxLines = 1)
        }
        
        OutlinedButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Default.Shuffle, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.shuffle), maxLines = 1)
        }

        if (isDownloading) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(
                onClick = { showDownloadAllDialog = true },
                enabled = isStreamingAllowed && isDownloadEnabled
            ) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.download_all))
            }
        }
    }
}
