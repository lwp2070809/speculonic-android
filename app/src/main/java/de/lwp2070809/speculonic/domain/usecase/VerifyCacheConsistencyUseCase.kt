package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.CacheValidator
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

sealed class VerifyCacheState {
    data class Progress(val percentage: Int, val status: String) : VerifyCacheState()
    data class Success(val inconsistentItems: List<InconsistentItem>) : VerifyCacheState()
    data class Error(val exception: Throwable) : VerifyCacheState()
}

class VerifyCacheConsistencyUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: de.lwp2070809.speculonic.data.db.dao.MusicDao
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    operator fun invoke(cacheLocation: String): Flow<VerifyCacheState> = flow {
        try {
            emit(VerifyCacheState.Progress(0, context.getString(R.string.sync_preparing)))
            val isSafEnabled = cacheLocation.isNotBlank()
            
            val downloadCache = CacheManager.getDownloadCache(context)
            val inconsistentList = mutableListOf<InconsistentItem>()
            val validator = CacheValidator(context)

            
            val cachedSongs = musicDao.getAllCachedSongs()
            if (cachedSongs.isNotEmpty()) {
                val groups = cachedSongs.groupBy { it.albumId ?: it.parent ?: "unknown" }
                val totalGroups = groups.size
                var processedGroups = 0

                for ((groupId, groupSongs) in groups) {
                    processedGroups++
                    val progressValue = (processedGroups * 90 / totalGroups)
                    emit(VerifyCacheState.Progress(progressValue, context.getString(R.string.verifying_binary_consistency, groupId)))

                    for (song in groupSongs) {
                        val physicalExists = if (isSafEnabled && song.localUri != null) {
                            try { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, song.localUri.toUri())?.exists() == true } catch(e: Exception) { false }
                        } else if (!isSafEnabled && song.localUri != null && song.localUri.startsWith("file:")) {
                            try {
                                val path = android.net.Uri.parse(song.localUri).path
                                path != null && java.io.File(path).exists()
                            } catch (e: Exception) { false }
                        } else {
                            downloadCache.keys.contains(song.id)
                        }

                        if (!physicalExists) {
                            inconsistentList.add(InconsistentItem(song.id, "${song.artist} - ${song.title}", InconsistentItem.Type.MISSING_FILE, song.localUri, song))
                            continue
                        }

                        if (isSafEnabled && song.localUri != null) {
                            val uri = song.localUri.toUri()
                            val isConsistent = validator.checkBinaryConsistency(uri, song, deepCheck = true)
                            if (!isConsistent) {
                                inconsistentList.add(InconsistentItem(song.id, "${song.artist} - ${song.title}", InconsistentItem.Type.BINARY_MISMATCH, song.localUri, song))
                            }
                        }
                    }
                }
            }

            
            if (isSafEnabled) {
                emit(VerifyCacheState.Progress(95, context.getString(R.string.sync_scanning_orphans)))
                try {
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, cacheLocation.toUri())
                    val idPattern = java.util.regex.Pattern.compile(".*\\[(.+)\\]\\.(mp3|flac|m4a|wav|aac|ogg|alac|aiff|dsf|lrc)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    val audioExtensions = de.lwp2070809.speculonic.util.FormatUtils.SUPPORTED_AUDIO_EXTENSIONS + "lrc"
                    
                    rootDoc?.listFiles()?.forEach { file ->
                        val fileName = file.name ?: ""
                        val matcher = idPattern.matcher(fileName)
                        val extension = fileName.substringAfterLast('.', "").lowercase()
                        
                        if (matcher.matches()) {
                            val songId = matcher.group(1) ?: ""
                            val songInDb = musicDao.getSongById(songId)
                            if (songInDb == null) {
                                inconsistentList.add(InconsistentItem(fileName, fileName, InconsistentItem.Type.ORPHANED_FILE, file.uri.toString(), null))
                            }
                        } else if (audioExtensions.contains(extension)) {
                            
                            val nameWithoutExt = fileName.substringBeforeLast(".")
                            if (nameWithoutExt.contains(" - ")) {
                                val parts = nameWithoutExt.split(" - ", limit = 2)
                                val artist = parts[0].trim()
                                val title = parts[1].trim()
                                val matchingSong = musicDao.getSongByTitleAndArtist(title, artist)
                                if (matchingSong == null) {
                                    inconsistentList.add(InconsistentItem(fileName, fileName, InconsistentItem.Type.ORPHANED_FILE, file.uri.toString(), null))
                                }
                            } else {
                                
                                inconsistentList.add(InconsistentItem(fileName, fileName, InconsistentItem.Type.ORPHANED_FILE, file.uri.toString(), null))
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("VerifyCacheConsistencyUseCase: Failed to scan SAF directory", e)
                }
            }

            emit(VerifyCacheState.Success(inconsistentList))
        } catch (e: Exception) {
            LogManager.e("VerifyCacheConsistencyUseCase: Interactive scan failed", e)
            emit(VerifyCacheState.Error(e))
        }
    }.flowOn(Dispatchers.IO)
}
