package de.lwp2070809.speculonic.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun DownloadManagerScreen(
    topBarState: TopBarState,
    onBackClick: () -> Unit
) {
    val title = stringResource(R.string.download_manager)
    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        topBarState.update(
            title = title,
            actions = {},
            showSearch = false,
            showBack = true,
            token = screenToken
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    val context = LocalContext.current
    val repository = LocalSubsonicRepository.current
    val downloadController = remember(repository) { DownloadController(context, repository) }

    val allDownloads by DownloadTracker.allDownloadsFlow.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val manualDownloads = remember(allDownloads) {
        allDownloads.filter { !isSilentDownload(it) }
    }

    val silentDownloads = remember(allDownloads) {
        allDownloads.filter { isSilentDownload(it) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.offline_downloads)) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.silent_cache_tab)) }
                )
            }

            val currentTasks = if (selectedTabIndex == 0) manualDownloads else silentDownloads

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        currentTasks.forEach { task ->
                            downloadController.cancelDownloadTaskOnly(task.request.id)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClearAll,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.clear_queue_keep_cache),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = {
                        currentTasks.forEach { task ->
                            if (task.state == Download.STATE_DOWNLOADING || task.state == Download.STATE_QUEUED) {
                                downloadController.pauseDownload(task.request.id)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.pause_all),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = {
                        currentTasks.forEach { task ->
                            if (task.state == Download.STATE_STOPPED) {
                                downloadController.resumeDownload(task.request.id)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.resume_all),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (currentTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = stringResource(R.string.no_download_tasks),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    items(currentTasks, key = { it.request.id }) { task ->
                        DownloadTaskItem(
                            task = task,
                            onPauseClick = { downloadController.pauseDownload(task.request.id) },
                            onResumeClick = { downloadController.resumeDownload(task.request.id) },
                            onCancelClick = { downloadController.removeDownload(task.request.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DownloadTaskItem(
    task: Download,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val title = remember(task.request.data) {
        try {
            if (task.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(task.request.data))
                json.optString("title", "Unknown Song")
            } else {
                "Unknown Song"
            }
        } catch (e: Exception) {
            "Unknown Song"
        }
    }

    val artist = remember(task.request.data) {
        try {
            if (task.request.data.isNotEmpty()) {
                val json = JSONObject(Util.fromUtf8Bytes(task.request.data))
                json.optString("artist", "Unknown Artist")
            } else {
                "Unknown Artist"
            }
        } catch (e: Exception) {
            "Unknown Artist"
        }
    }

    val progress = task.percentDownloaded.coerceAtLeast(0f)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val isSilent = isSilentDownload(task)
                val isSilentBufferingOrQueuing = isSilent && progress == 0f && (task.state == Download.STATE_DOWNLOADING || task.state == Download.STATE_QUEUED)

                val statusLabel = if (isSilentBufferingOrQueuing) {
                    if (task.state == Download.STATE_QUEUED) stringResource(R.string.silent_queued_status)
                    else stringResource(R.string.silent_downloading_status)
                } else {
                    getStatusLabel(task.state, progress)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = getStatusColor(task.state)
                    )
                    if (!isSilentBufferingOrQueuing && (task.state == Download.STATE_DOWNLOADING || task.state == Download.STATE_STOPPED)) {
                        Text(
                            text = "${progress.toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isSilentBufferingOrQueuing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else if (task.state == Download.STATE_DOWNLOADING || task.state == Download.STATE_STOPPED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (task.state == Download.STATE_STOPPED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (task.state) {
                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                        IconButton(onClick = onPauseClick) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "暂停",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Download.STATE_STOPPED -> {
                        IconButton(onClick = onResumeClick) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "继续",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                IconButton(onClick = onCancelClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消/删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun getStatusLabel(state: Int, progress: Float): String {
    return when (state) {
        Download.STATE_QUEUED -> "排队中"
        Download.STATE_DOWNLOADING -> "下载中"
        Download.STATE_COMPLETED -> "已完成"
        Download.STATE_FAILED -> "失败"
        Download.STATE_STOPPED -> "已暂停"
        Download.STATE_REMOVING -> "正在删除"
        Download.STATE_RESTARTING -> "正在重试"
        else -> "未知"
    }
}

@Composable
fun getStatusColor(state: Int): Color {
    return when (state) {
        Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> MaterialTheme.colorScheme.primary
        Download.STATE_COMPLETED -> MaterialTheme.colorScheme.secondary
        Download.STATE_FAILED -> MaterialTheme.colorScheme.error
        Download.STATE_STOPPED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(UnstableApi::class)
private fun isSilentDownload(download: Download): Boolean {
    return try {
        if (download.request.data.isNotEmpty()) {
            val json = JSONObject(Util.fromUtf8Bytes(download.request.data))
            json.optBoolean("isSilent", false)
        } else false
    } catch (e: Exception) {
        false
    }
}
