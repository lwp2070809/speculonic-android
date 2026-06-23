package de.lwp2070809.speculonic.ui.components

import de.lwp2070809.speculonic.R

import android.content.Context
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.MessageDigest

@Composable
fun SongDetailDialog(
    song: Song,
    onDismiss: () -> Unit
) {
    val repository = LocalSubsonicRepository.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songEntity by repository.musicDaoGet.getSongByIdFlow(song.id).collectAsState(initial = null)

    var sha1 by remember { mutableStateOf<String?>(null) }
    var id3Metadata by remember { mutableStateOf<Map<String, String>?>(null) }
    var remoteSong by remember { mutableStateOf<Song?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }

    val pagerState = rememberPagerState(pageCount = { 3 })

    LaunchedEffect(pagerState.currentPage, songEntity?.localUri) {
        val uri = songEntity?.localUri
        if (uri != null) {
            if (pagerState.currentPage == 0 && sha1 == null) {
                sha1 = withContext(Dispatchers.IO) { calculateSha1(uri, context) }
            } else if (pagerState.currentPage == 1 && id3Metadata == null) {
                id3Metadata = withContext(Dispatchers.IO) { extractId3(uri, songEntity?.suffix, context) }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2 && remoteSong == null && !remoteLoading) {
            if (de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.isOfflineOrUnreachable()) {
                remoteError = "OFFLINE"
            } else {
                remoteLoading = true
                val result = repository.getSongRemote(song.id)
                if (result.isSuccess) {
                    remoteSong = result.getOrNull()
                } else {
                    if (de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.isOfflineOrUnreachable()) {
                        remoteError = "OFFLINE"
                    } else {
                        remoteError = result.exceptionOrNull()?.message ?: context.getString(R.string.failed_to_fetch_remote)
                    }
                }
                remoteLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.song_details)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp) 
            ) {
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text(stringResource(R.string.general_info)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.metadata_tab)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        text = { Text(stringResource(R.string.remote_tab)) }
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp)
                    ) {
                        when (page) {
                            0 -> LocalDbTab(songEntity, sha1)
                            1 -> Id3Tab(id3Metadata, songEntity?.isFullyCached == true)
                            2 -> RemoteTab(songEntity, remoteSong, remoteLoading, remoteError)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

@Composable
private fun LocalDbTab(songEntity: de.lwp2070809.speculonic.data.db.entities.SongEntity?, sha1: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (songEntity == null) {
            Text(stringResource(R.string.loading_local_data))
            return@Column
        }
        DetailItem(stringResource(R.string.id), songEntity.id)
        DetailItem(stringResource(R.string.title), songEntity.title)
        DetailItem(stringResource(R.string.artist), songEntity.artist ?: "-")
        DetailItem(stringResource(R.string.artist_id), songEntity.artistId ?: "-")
        DetailItem(stringResource(R.string.album), songEntity.album ?: "-")
        DetailItem(stringResource(R.string.album_id), songEntity.albumId ?: "-")
        DetailItem(stringResource(R.string.track), songEntity.track?.toString() ?: "-")
        DetailItem(stringResource(R.string.year), songEntity.year?.toString() ?: "-")
        DetailItem(stringResource(R.string.genre), songEntity.genre ?: "-")
        DetailItem(stringResource(R.string.duration), FormatUtils.formatDuration(songEntity.duration ?: 0))
        DetailItem(stringResource(R.string.file_format), songEntity.suffix?.uppercase() ?: "-")
        DetailItem(stringResource(R.string.bitrate), songEntity.bitRate?.let { "$it kbps" } ?: "-")
        DetailItem(stringResource(R.string.file_size), FormatUtils.formatSize(songEntity.size ?: 0))
        DetailItem(stringResource(R.string.path), songEntity.path ?: "-")
        DetailItem(stringResource(R.string.content_type), songEntity.contentType ?: "-")
        DetailItem(stringResource(R.string.starred_status), songEntity.starred.toString())
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        val displayUri = songEntity.localUri?.let { FormatUtils.getFullPhysicalPath(it) } ?: stringResource(R.string.not_available_not_cached)
        DetailItem(stringResource(R.string.physical_path), displayUri)
        DetailItem(stringResource(R.string.sha_1), sha1 ?: if (songEntity.localUri != null) stringResource(R.string.calculating) else stringResource(R.string.not_available_not_cached))
    }
}

@Composable
private fun Id3Tab(id3Metadata: Map<String, String>?, isCached: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!isCached) {
            Text(
                text = stringResource(R.string.metadata_not_cached_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        if (id3Metadata == null) {
            Text(stringResource(R.string.reading_metadata))
            return@Column
        }
        if (id3Metadata.isEmpty()) {
            Text(stringResource(R.string.no_metadata_found))
            return@Column
        }
        id3Metadata.filterKeys { it != "__FORMAT_INFO__" }.forEach { (key, value) ->
            if (key.startsWith("Lyrics")) {
                Column {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            } else {
                DetailItem(key, value)
            }
        }
        
        val formatInfo = id3Metadata["__FORMAT_INFO__"]
        if (formatInfo != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "${stringResource(R.string.container_format_and_version)}: $formatInfo",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RemoteTab(
    localSong: de.lwp2070809.speculonic.data.db.entities.SongEntity?,
    remoteSong: Song?,
    isLoading: Boolean,
    error: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        if (error != null) {
            val displayError = if (error == "OFFLINE") {
                stringResource(R.string.cloud_compare_offline_error)
            } else {
                error
            }
            Text(text = displayError, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        if (remoteSong == null || localSong == null) {
            Text(stringResource(R.string.no_remote_data))
            return@Column
        }

        val context = LocalContext.current
        val diffs = remember(localSong, remoteSong) { compareSongs(context, localSong, remoteSong) }
        if (diffs.isEmpty()) {
            Text(
                text = stringResource(R.string.data_synchronized),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Text(
                text = stringResource(R.string.differences_found),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            diffs.forEach { diff ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(diff.field, style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f).background(Color.Red.copy(alpha = 0.1f)).padding(4.dp)) {
                            Text(stringResource(R.string.local_label), style = MaterialTheme.typography.labelSmall)
                            Text(diff.localValue, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f).background(Color.Green.copy(alpha = 0.1f)).padding(4.dp)) {
                            Text(stringResource(R.string.remote_label), style = MaterialTheme.typography.labelSmall)
                            Text(diff.remoteValue, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private data class DiffItem(val field: String, val localValue: String, val remoteValue: String)

private fun compareSongs(
    context: Context,
    local: de.lwp2070809.speculonic.data.db.entities.SongEntity,
    remote: Song
): List<DiffItem> {
    val diffs = mutableListOf<DiffItem>()
    fun check(field: String, l: Any?, r: Any?) {
        val ls = l?.toString() ?: "-"
        val rs = r?.toString() ?: "-"
        if (ls != rs) diffs.add(DiffItem(field, ls, rs))
    }

    check(context.getString(R.string.title), local.title, remote.title)
    check(context.getString(R.string.artist), local.artist, remote.artist)
    check(context.getString(R.string.album), local.album, remote.album)
    check(context.getString(R.string.duration), local.duration, remote.duration)
    check(context.getString(R.string.suffix_label), local.suffix, remote.suffix)
    check(context.getString(R.string.bitrate), local.bitRate, remote.bitRate)
    check(context.getString(R.string.file_size), local.size, remote.size)
    check(context.getString(R.string.path), local.path, remote.path)

    return diffs
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

private suspend fun calculateSha1(uriString: String, context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val digest = MessageDigest.getInstance("SHA-1")
        val uri = uriString.toUri()
        val inputStream = if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)
        } else if (uri.scheme == "file") {
            FileInputStream(uri.path)
        } else {
            FileInputStream(uriString)
        }
        inputStream?.use { fis ->
            val buffer = ByteArray(8192)
            var n = fis.read(buffer)
            while (n != -1) {
                digest.update(buffer, 0, n)
                n = fis.read(buffer)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }
}

private suspend fun extractId3(uriString: String, suffix: String?, context: Context): Map<String, String>? {
    return withContext(Dispatchers.IO) {
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().isAndroid = true
            
            val physicalPath = FormatUtils.getFullPhysicalPath(uriString)
            var file = java.io.File(physicalPath)
            
            if ((!file.exists() || !file.canRead()) && uriString.startsWith("content://")) {
                val ext = suffix?.lowercase() ?: uriString.substringAfterLast('.', "").substringBefore('?').lowercase().takeIf { it.isNotEmpty() } ?: "mp3"
                val tempFile = java.io.File.createTempFile("temp_tag_parsing", ".$ext", context.cacheDir)
                context.contentResolver.openInputStream(uriString.toUri())?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                file = tempFile
            }
            
            if (!file.exists() || !file.canRead()) return@withContext emptyMap()

            val metadataMap = mutableMapOf<String, String>()
            
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
            
            val header = audioFile.audioHeader
            if (header != null) {
                metadataMap["Encoding Format"] = header.format ?: ""
                metadataMap["Sample Rate"] = header.sampleRate?.let { "$it Hz" } ?: ""
                metadataMap["Channels"] = header.channels ?: ""
                metadataMap["BitRate (Internal)"] = header.bitRate?.let { "$it kbps" } ?: ""
            }
            
            val formatStr = buildString {
                if (header != null) {
                    append(header.format ?: "")
                }
                val tag = audioFile.tag
                if (tag != null) {
                    val tagType = tag.javaClass.simpleName.replace("Tag", "")
                    if (isNotEmpty() && tagType.isNotEmpty()) append(" / ")
                    append(tagType)
                }
            }
            if (formatStr.isNotEmpty()) {
                metadataMap["__FORMAT_INFO__"] = formatStr
            }
            
            val tag = audioFile.tag
            if (tag != null) {
                metadataMap["Title"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) ?: ""
                metadataMap["Artist"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST) ?: ""
                metadataMap["Album"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM) ?: ""
                metadataMap["Year"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR) ?: ""
                metadataMap["Track"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK) ?: ""
                metadataMap["Genre"] = tag.getFirst(org.jaudiotagger.tag.FieldKey.GENRE) ?: ""
                
                val lyrics = tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)
                if (!lyrics.isNullOrEmpty()) {
                    metadataMap["Lyrics (Parsed)"] = lyrics
                }
                
                val fields = tag.fields
                while (fields.hasNext()) {
                    val field = fields.next()
                    val key = field.id
                    val value = if (field.isBinary) "[Binary Data]" else field.toString()
                    
                    if (!key.equals("APIC", ignoreCase = true) && !key.equals("PIC", ignoreCase = true)) {
                        val cleanValue = value.replace(Regex("^Text=\"?|\"?$"), "").trim()
                        metadataMap["Raw: $key"] = cleanValue
                    }
                }
                
                val hasCover = tag.firstArtwork != null
                metadataMap["Embedded Cover"] = if (hasCover) "Yes" else "No"
            }
            
            if (file.absolutePath.contains(context.cacheDir.absolutePath)) {
                file.delete()
            }
            
            metadataMap.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }
}
