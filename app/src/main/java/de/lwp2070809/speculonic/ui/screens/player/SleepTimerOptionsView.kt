package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.playback.SleepTimerMode
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun SleepTimerOptionsView(
    currentMode: SleepTimerMode,
    lastMinutes: Int,
    lastSongCount: Int,
    onModeSelected: (SleepTimerMode, minutes: Int, songCount: Int) -> Unit,
    onCancelTimer: () -> Unit
) {
    var showTimeDialog by remember { mutableStateOf(false) }
    var showSongsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.sleep_timer),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        
        SleepTimerOptionRow(
            label = stringResource(R.string.after_time, FormatUtils.formatDuration(lastMinutes * 60 * 1000L)),
            selected = currentMode == SleepTimerMode.TIME,
            onClick = { showTimeDialog = true }
        )
        
        
        SleepTimerOptionRow(
            label = androidx.compose.ui.res.pluralStringResource(R.plurals.after_songs, lastSongCount, lastSongCount),
            selected = currentMode == SleepTimerMode.SONG_COUNT,
            onClick = { showSongsDialog = true }
        )
        
        
        SleepTimerOptionRow(
            label = stringResource(R.string.end_of_playlist),
            selected = currentMode == SleepTimerMode.END_OF_PLAYLIST,
            onClick = { onModeSelected(SleepTimerMode.END_OF_PLAYLIST, 0, 0) }
        )
        
        if (currentMode != SleepTimerMode.OFF) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { 
                    Text(
                        text = stringResource(R.string.cancel_timer),
                        color = MaterialTheme.colorScheme.error
                    ) 
                },
                modifier = Modifier.clickable { onCancelTimer() }
            )
        }
    }

    if (showTimeDialog) {
        NumberInputDialog(
            title = stringResource(R.string.set_minutes),
            initialValue = lastMinutes.toString(),
            onDismiss = { showTimeDialog = false },
            onConfirm = { 
                val minutes = it.toIntOrNull() ?: lastMinutes
                onModeSelected(SleepTimerMode.TIME, minutes, 0)
                showTimeDialog = false
            }
        )
    }

    if (showSongsDialog) {
        NumberInputDialog(
            title = stringResource(R.string.set_song_count),
            initialValue = lastSongCount.toString(),
            onDismiss = { showSongsDialog = false },
            onConfirm = { 
                val count = it.toIntOrNull() ?: lastSongCount
                onModeSelected(SleepTimerMode.SONG_COUNT, 0, count)
                showSongsDialog = false
            }
        )
    }
}

@Composable
private fun SleepTimerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.all { char -> char.isDigit() }) value = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
