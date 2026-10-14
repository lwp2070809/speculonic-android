package de.lwp2070809.speculonic.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.playback.SleepTimerMode
import de.lwp2070809.speculonic.ui.components.WheelPicker
import de.lwp2070809.speculonic.util.FormatUtils

@Composable
fun SleepTimerOptionsView(
    currentMode: SleepTimerMode,
    lastMinutes: Int,
    lastSongCount: Int,
    isTimerRunning: Boolean = false,
    remainingMillis: Long = 0L,
    songsRemaining: Int = 0,
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

        // 倒计时选项行
        val timeLabel = if (isTimerRunning && currentMode == SleepTimerMode.TIME) {
            stringResource(R.string.after_time, FormatUtils.formatDuration(remainingMillis))
        } else {
            stringResource(R.string.after_time, FormatUtils.formatDuration(lastMinutes * 60 * 1000L))
        }
        SleepTimerOptionRow(
            label = timeLabel,
            selected = currentMode == SleepTimerMode.TIME,
            onClick = { showTimeDialog = true }
        )

        // 剩余歌曲数选项行
        val songsLabel = if (isTimerRunning && currentMode == SleepTimerMode.SONG_COUNT) {
            pluralStringResource(R.plurals.after_songs, songsRemaining, songsRemaining)
        } else {
            pluralStringResource(R.plurals.after_songs, lastSongCount, lastSongCount)
        }
        SleepTimerOptionRow(
            label = songsLabel,
            selected = currentMode == SleepTimerMode.SONG_COUNT,
            onClick = { showSongsDialog = true }
        )

        // 播放列表末尾选项行
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
        val initialMinutes = remember(showTimeDialog) {
            if (isTimerRunning && currentMode == SleepTimerMode.TIME && remainingMillis > 0) {
                ((remainingMillis + 59999L) / 60000L).toInt()
            } else {
                lastMinutes.coerceAtLeast(1)
            }
        }
        val initialHours = remember(initialMinutes) { (initialMinutes / 60).coerceIn(0, 23) }
        val initialMinutesOnly = remember(initialMinutes) { (initialMinutes % 60).coerceIn(0, 59) }

        SleepTimerTimePickerDialog(
            initialHours = initialHours,
            initialMinutes = initialMinutesOnly,
            onDismiss = { showTimeDialog = false },
            onConfirm = { minutes ->
                if (minutes == 0) {
                    onCancelTimer()
                } else {
                    onModeSelected(SleepTimerMode.TIME, minutes, 0)
                }
                showTimeDialog = false
            }
        )
    }

    if (showSongsDialog) {
        val initialCount = remember(showSongsDialog) {
            if (isTimerRunning && currentMode == SleepTimerMode.SONG_COUNT && songsRemaining > 0) {
                songsRemaining.coerceIn(1, 100)
            } else {
                lastSongCount.coerceIn(1, 100)
            }
        }

        SleepTimerSongsPickerDialog(
            initialSongCount = initialCount,
            onDismiss = { showSongsDialog = false },
            onConfirm = { count ->
                if (count == 0) {
                    onCancelTimer()
                } else {
                    onModeSelected(SleepTimerMode.SONG_COUNT, 0, count)
                }
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

/**
 * 倒计时滚轮选择对话框（支持小时与分钟独立无限循环滚动）。
 */
@Composable
private fun SleepTimerTimePickerDialog(
    initialHours: Int,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (totalMinutes: Int) -> Unit
) {
    val hoursList = remember { (0..23).toList() }
    val minutesList = remember { (0..59).toList() }

    var selectedHours by remember { mutableIntStateOf(initialHours.coerceIn(0, 23)) }
    var selectedMinutes by remember { mutableIntStateOf(initialMinutes.coerceIn(0, 59)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_countdown)) },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 小时滚轮
                WheelPicker(
                    items = hoursList,
                    initialSelectedIndex = initialHours.coerceIn(0, 23),
                    isInfinite = true,
                    formatLabel = { it.toString().padStart(2, '0') },
                    modifier = Modifier.width(64.dp),
                    onSelectedIndexChanged = { selectedHours = hoursList[it] }
                )
                Text(
                    text = stringResource(R.string.unit_hours),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp)
                )

                // 分钟滚轮
                WheelPicker(
                    items = minutesList,
                    initialSelectedIndex = initialMinutes.coerceIn(0, 59),
                    isInfinite = true,
                    formatLabel = { it.toString().padStart(2, '0') },
                    modifier = Modifier.width(64.dp),
                    onSelectedIndexChanged = { selectedMinutes = minutesList[it] }
                )
                Text(
                    text = stringResource(R.string.unit_minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        },
        confirmButton = {
            val isEnabled = selectedHours > 0 || selectedMinutes > 0
            TextButton(
                onClick = { onConfirm(selectedHours * 60 + selectedMinutes) },
                enabled = isEnabled
            ) {
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

/**
 * 剩余曲目滚轮选择对话框（支持 1~100 首曲目滚动）。
 */
@Composable
private fun SleepTimerSongsPickerDialog(
    initialSongCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (count: Int) -> Unit
) {
    val songsList = remember { (1..100).toList() }
    val safeInitialIndex = (initialSongCount - 1).coerceIn(0, songsList.lastIndex)
    var selectedCount by remember { mutableIntStateOf(songsList[safeInitialIndex]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_song_count)) },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                WheelPicker(
                    items = songsList,
                    initialSelectedIndex = safeInitialIndex,
                    isInfinite = false,
                    formatLabel = { it.toString() },
                    modifier = Modifier.width(72.dp),
                    onSelectedIndexChanged = { selectedCount = songsList[it] }
                )
                Text(
                    text = stringResource(R.string.unit_songs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedCount) }) {
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
