package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ActionButtonsRow(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onDownloadAll: () -> Unit,
    isStreamingAllowed: Boolean,
    isDownloading: Boolean,
    isDownloadEnabled: Boolean = true,
    isPlayAllEnabled: Boolean = true,
    isShuffleEnabled: Boolean = true
) {
    var showDownloadAllDialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

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
            enabled = isPlayAllEnabled,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.play_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        
        OutlinedButton(
            onClick = onShuffle,
            enabled = isShuffleEnabled,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                painterResource(id = R.drawable.ic_symbol_shuffle),
                contentDescription = null
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.shuffle), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Icon(
                    painterResource(id = R.drawable.ic_symbol_download),
                    contentDescription = stringResource(R.string.download_all)
                )
            }
        }
    }
}
