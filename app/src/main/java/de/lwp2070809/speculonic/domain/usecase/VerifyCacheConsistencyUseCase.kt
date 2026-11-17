package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.CacheValidator
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
            
            val downloadCache = CacheManager.acquireDownloadCache(context)
            val inconsistentList = mutableListOf<InconsistentItem>()
            val validator = CacheValidator(context)
            val safEntries = if (isSafEnabled) {
                de.lwp2070809.speculonic.data.SafDirectoryHelper.listChildEntries(context, cacheLocation.toUri())
            } else {
                emptyList()
            }
            val safUriSet = safEntries.mapTo(HashSet(safEntries.size)) { it.uri.toString() }

            val cachedSongs = musicDao.getAllCachedSongs()
            if (cachedSongs.isNotEmpty()) {
                val totalFiles = cachedSongs.size
                var processedFiles = 0
                var lastEmittedProgress = -1
                var lastEmitTimeMs = 0L

                for (song in cachedSongs) {
                    processedFiles++
                    val progressValue = (processedFiles * 90 / totalFiles)
                    val nowMs = SystemClock.elapsedRealtime()
                    val shouldEmit = processedFiles == 1 ||
                        processedFiles == totalFiles ||
                        (progressValue != lastEmittedProgress && nowMs - lastEmitTimeMs >= 50L) ||
                        (nowMs - lastEmitTimeMs >= 250L)
                    if (shouldEmit) {
                        lastEmittedProgress = progressValue
                        lastEmitTimeMs = nowMs
                        emit(
                            VerifyCacheState.Progress(
                                progressValue,
                                context.getString(R.string.verifying_binary_consistency, "$processedFiles/$totalFiles")
                            )
                        )
                    }
                    val physicalExists = if (isSafEnabled && song.localUri != null) {
                        when {
                            song.localUri in safUriSet -> true
                            song.localUri.startsWith("file:") -> {
                                val path = song.localUri.toUri().path
                                path != null && java.io.File(path).exists()
                            }
                            else -> {
                                try {
                                    androidx.documentfile.provider.DocumentFile.fromSingleUri(
                                        context,
                                        song.localUri.toUri()
                                    )?.exists() == true
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    false
                                }
                            }
                        }
                    } else if (!isSafEnabled && song.localUri != null && song.localUri.startsWith("file:")) {
                        try {
                            val path = song.localUri.toUri().path
                            path != null && java.io.File(path).exists()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            false
                        }
                    } else {
                        downloadCache.keys.contains(song.id)
                    }

                    if (!physicalExists) {
                        inconsistentList.add(InconsistentItem(song.id, "${song.artist} - ${song.title}", InconsistentItem.Type.MISSING_FILE, song.localUri, song))
                        continue
                    }

                    if (song.localUri != null) {
                        val uri = song.localUri.toUri()
                        val isConsistent = validator.checkBinaryConsistency(uri, song, deepCheck = true)
                        if (!isConsistent) {
                            inconsistentList.add(InconsistentItem(song.id, "${song.artist} - ${song.title}", InconsistentItem.Type.BINARY_MISMATCH, song.localUri, song))
                        }
                    }
                }
            }

            if (isSafEnabled && safEntries.isNotEmpty()) {
                emit(VerifyCacheState.Progress(95, context.getString(R.string.sync_scanning_orphans)))
                try {
                    val audioExtensions = de.lwp2070809.speculonic.util.MediaFormatUtils.ALL_SUPPORTED_AUDIO_EXTENSIONS +
                        de.lwp2070809.speculonic.util.MediaFormatUtils.EXTENSION_LRC
                    val validSafUris = cachedSongs.mapNotNull { it.localUri }.toSet()
                    val validSafLrcUris = validSafUris.map {
                        de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(it)
                    }.toSet()

                    val unknownEntries = safEntries.filter { entry ->
                        val uriStr = entry.uri.toString()
                        uriStr !in validSafUris && uriStr !in validSafLrcUris
                    }
                    val extractedIds = unknownEntries.mapNotNull {
                        de.lwp2070809.speculonic.util.MediaFormatUtils.extractSongIdFromFileName(it.name)
                    }.distinct()
                    val dbSongsMap = if (extractedIds.isNotEmpty()) {
                        musicDao.getSongsByIds(extractedIds).associateBy { it.id }
                    } else {
                        emptyMap()
                    }

                    unknownEntries.forEach { entry ->
                        val fileUriStr = entry.uri.toString()
                        val fileName = entry.name
                        val songId = de.lwp2070809.speculonic.util.MediaFormatUtils.extractSongIdFromFileName(fileName)
                        val extension = fileName.substringAfterLast('.', "").lowercase()

                        if (songId != null) {
                            val songInDb = dbSongsMap[songId]
                            if (songInDb == null || !songInDb.isFullyCached) {
                                inconsistentList.add(
                                    InconsistentItem(
                                        fileName,
                                        fileName,
                                        InconsistentItem.Type.ORPHANED_FILE,
                                        fileUriStr,
                                        songInDb
                                    )
                                )
                            }
                        } else if (audioExtensions.contains(extension)) {
                            val nameWithoutExt = fileName.substringBeforeLast(".")
                            if (nameWithoutExt.contains(" - ")) {
                                val parts = nameWithoutExt.split(" - ", limit = 2)
                                val artist = parts[0].trim()
                                val title = parts[1].trim()
                                val matchingSong = musicDao.getSongByTitleAndArtist(title, artist)
                                if (matchingSong == null || !matchingSong.isFullyCached) {
                                    inconsistentList.add(
                                        InconsistentItem(
                                            fileName,
                                            fileName,
                                            InconsistentItem.Type.ORPHANED_FILE,
                                            fileUriStr,
                                            matchingSong
                                        )
                                    )
                                }
                            } else {
                                inconsistentList.add(
                                    InconsistentItem(
                                        fileName,
                                        fileName,
                                        InconsistentItem.Type.ORPHANED_FILE,
                                        fileUriStr,
                                        null
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    LogManager.e("VerifyCacheConsistencyUseCase: Failed to scan SAF directory", e)
                }
            }

            emit(VerifyCacheState.Success(inconsistentList))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogManager.e("VerifyCacheConsistencyUseCase: Interactive scan failed", e)
            emit(VerifyCacheState.Error(e))
        }
    }.flowOn(Dispatchers.IO)
}
