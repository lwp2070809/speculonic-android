package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.CacheValidator
import de.lwp2070809.speculonic.data.SafChildEntry
import de.lwp2070809.speculonic.data.SafDirectoryHelper
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.MediaFormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

sealed class VerifyCacheState {
    data class Progress(val percentage: Int, val status: String) : VerifyCacheState()
    data class Success(val inconsistentItems: List<InconsistentItem>) : VerifyCacheState()
    data class Error(val exception: Throwable) : VerifyCacheState()
}

class VerifyCacheConsistencyUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val cacheManager: CacheManager
) {
    private data class ProgressTracker(
        var lastEmittedProgress: Int = -1,
        var lastEmitTimeMs: Long = 0L
    )

    @OptIn(UnstableApi::class)
    operator fun invoke(cacheLocation: String): Flow<VerifyCacheState> = flow {
        try {
            emit(VerifyCacheState.Progress(0, context.getString(R.string.sync_preparing)))
            val isSafEnabled = cacheLocation.isNotBlank()
            val downloadCache = cacheManager.acquireDownloadCache()
            val inconsistentList = mutableListOf<InconsistentItem>()
            val validator = CacheValidator(context)
            val safEntries = if (isSafEnabled) {
                SafDirectoryHelper.listChildEntries(context, cacheLocation.toUri())
            } else {
                emptyList()
            }
            val safUriSet = safEntries.mapTo(HashSet(safEntries.size)) { it.uri.toString() }
            val cachedSongs = musicDao.getAllCachedSongs()

            verifyCachedSongsList(
                cachedSongs = cachedSongs,
                isSafEnabled = isSafEnabled,
                safUriSet = safUriSet,
                downloadCache = downloadCache,
                validator = validator,
                inconsistentList = inconsistentList
            )

            if (isSafEnabled && safEntries.isNotEmpty()) {
                emit(VerifyCacheState.Progress(ORPHAN_SCAN_PROGRESS, context.getString(R.string.sync_scanning_orphans)))
                scanOrphanedSafEntries(cachedSongs, safEntries, inconsistentList)
            }

            emit(VerifyCacheState.Success(inconsistentList))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("VerifyCacheConsistencyUseCase: Interactive scan failed", e)
            emit(VerifyCacheState.Error(e))
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(UnstableApi::class)
    private suspend fun FlowCollector<VerifyCacheState>.verifyCachedSongsList(
        cachedSongs: List<SongEntity>,
        isSafEnabled: Boolean,
        safUriSet: Set<String>,
        downloadCache: Cache,
        validator: CacheValidator,
        inconsistentList: MutableList<InconsistentItem>
    ) {
        if (cachedSongs.isEmpty()) return
        val totalFiles = cachedSongs.size
        val tracker = ProgressTracker()

        cachedSongs.forEachIndexed { index, song ->
            emitProgressIfNeeded(index + 1, totalFiles, tracker)
            val physicalExists = checkPhysicalSongExists(song, isSafEnabled, safUriSet, downloadCache)
            if (!physicalExists) {
                inconsistentList.add(
                    InconsistentItem(
                        song.id,
                        "${song.artist} - ${song.title}",
                        InconsistentItem.Type.MISSING_FILE,
                        song.localUri,
                        song
                    )
                )
            } else if (song.localUri != null &&
                !validator.checkBinaryConsistency(song.localUri.toUri(), song, deepCheck = true)
            ) {
                inconsistentList.add(
                    InconsistentItem(
                        song.id,
                        "${song.artist} - ${song.title}",
                        InconsistentItem.Type.BINARY_MISMATCH,
                        song.localUri,
                        song
                    )
                )
            }
        }
    }

    private suspend fun FlowCollector<VerifyCacheState>.emitProgressIfNeeded(
        processedFiles: Int,
        totalFiles: Int,
        tracker: ProgressTracker
    ) {
        val progressValue = processedFiles * VERIFY_PROGRESS_WEIGHT / totalFiles
        val nowMs = SystemClock.elapsedRealtime()
        val shouldEmit = processedFiles == 1 ||
            processedFiles == totalFiles ||
            (progressValue != tracker.lastEmittedProgress && nowMs - tracker.lastEmitTimeMs >= PROGRESS_MIN_INTERVAL_MS) ||
            (nowMs - tracker.lastEmitTimeMs >= PROGRESS_HEARTBEAT_MS)
        if (shouldEmit) {
            tracker.lastEmittedProgress = progressValue
            tracker.lastEmitTimeMs = nowMs
            emit(
                VerifyCacheState.Progress(
                    progressValue,
                    context.getString(R.string.verifying_binary_consistency, "$processedFiles/$totalFiles")
                )
            )
        }
    }

    @OptIn(UnstableApi::class)
    private fun checkPhysicalSongExists(
        song: SongEntity,
        isSafEnabled: Boolean,
        safUriSet: Set<String>,
        downloadCache: Cache
    ): Boolean {
        val localUri = song.localUri
        return when {
            isSafEnabled && localUri != null -> checkSafUriExists(localUri, safUriSet)
            !isSafEnabled && localUri != null && localUri.startsWith("file:") -> checkLocalFileExists(localUri)
            else -> downloadCache.keys.contains(song.id)
        }
    }

    private fun checkSafUriExists(localUri: String, safUriSet: Set<String>): Boolean {
        if (localUri in safUriSet) return true
        if (localUri.startsWith("file:")) return checkLocalFileExists(localUri)
        return try {
            DocumentFile.fromSingleUri(context, localUri.toUri())?.exists() == true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("VerifyCacheConsistencyUseCase: Failed checking SAF document existence for $localUri", e)
            false
        }
    }

    private fun checkLocalFileExists(localUri: String): Boolean {
        return try {
            val path = localUri.toUri().path
            path != null && File(path).exists()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("VerifyCacheConsistencyUseCase: Failed checking local file existence for $localUri", e)
            false
        }
    }

    private suspend fun scanOrphanedSafEntries(
        cachedSongs: List<SongEntity>,
        safEntries: List<SafChildEntry>,
        inconsistentList: MutableList<InconsistentItem>
    ) {
        try {
            val validSafUris = cachedSongs.mapNotNull { it.localUri }.toSet()
            val validSafLrcUris = validSafUris.map { FormatUtils.replaceExtensionWithLrc(it) }.toSet()
            val unknownEntries = safEntries.filter { entry ->
                val uriStr = entry.uri.toString()
                uriStr !in validSafUris && uriStr !in validSafLrcUris
            }
            val extractedIds = unknownEntries.mapNotNull {
                MediaFormatUtils.extractSongIdFromFileName(it.name)
            }.distinct()
            val dbSongsMap = if (extractedIds.isNotEmpty()) {
                musicDao.getSongsByIds(extractedIds).associateBy { it.id }
            } else {
                emptyMap()
            }
            val supportedExtensions = MediaFormatUtils.ALL_SUPPORTED_AUDIO_EXTENSIONS + MediaFormatUtils.EXTENSION_LRC

            unknownEntries.forEach { entry ->
                classifyOrphanedEntry(entry, dbSongsMap, supportedExtensions)?.let { inconsistentList.add(it) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.e("VerifyCacheConsistencyUseCase: Failed to scan SAF directory", e)
        }
    }

    private suspend fun classifyOrphanedEntry(
        entry: SafChildEntry,
        dbSongsMap: Map<String, SongEntity>,
        supportedExtensions: Set<String>
    ): InconsistentItem? {
        val fileUriStr = entry.uri.toString()
        val fileName = entry.name
        val songId = MediaFormatUtils.extractSongIdFromFileName(fileName)
        if (songId != null) {
            val songInDb = dbSongsMap[songId]
            return if (songInDb == null || !songInDb.isFullyCached) {
                InconsistentItem(fileName, fileName, InconsistentItem.Type.ORPHANED_FILE, fileUriStr, songInDb)
            } else {
                null
            }
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (!supportedExtensions.contains(extension)) return null

        val matchingSong = findSongByFileNameConvention(fileName)
        return if (matchingSong == null || !matchingSong.isFullyCached) {
            InconsistentItem(fileName, fileName, InconsistentItem.Type.ORPHANED_FILE, fileUriStr, matchingSong)
        } else {
            null
        }
    }

    private suspend fun findSongByFileNameConvention(fileName: String): SongEntity? {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        if (!nameWithoutExt.contains(" - ")) return null
        val parts = nameWithoutExt.split(" - ", limit = 2)
        return musicDao.getSongByTitleAndArtist(parts[1].trim(), parts[0].trim())
    }

    private companion object {
        const val VERIFY_PROGRESS_WEIGHT = 90
        const val PROGRESS_MIN_INTERVAL_MS = 50L
        const val PROGRESS_HEARTBEAT_MS = 250L
        const val ORPHAN_SCAN_PROGRESS = 95
    }
}
