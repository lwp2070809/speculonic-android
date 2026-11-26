package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import android.provider.DocumentsContract
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
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
            val safByUri = safEntries.associateBy { it.uri.toString() }
            if (isSafEnabled) validator.rememberDirectory(cacheLocation.toUri(), safEntries)
            val cachedSongs = musicDao.getAllCachedSongs()

            verifyCachedSongsList(
                cachedSongs = cachedSongs,
                safByUri = safByUri,
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
        safByUri: Map<String, SafChildEntry>,
        downloadCache: Cache,
        validator: CacheValidator,
        inconsistentList: MutableList<InconsistentItem>
    ) {
        if (cachedSongs.isEmpty()) return
        val totalFiles = cachedSongs.size
        val tracker = ProgressTracker()

        cachedSongs.forEachIndexed { index, song ->
            emitProgressIfNeeded(index + 1, totalFiles, tracker)
            val uri = song.localUri
            val issue = if (uri != null) {
                validator.inspectFile(uri.toUri(), song, listedSize = safByUri[uri]?.size?.takeIf { it > 0 })
            } else {
                validator.inspectDownloadCache(song, downloadCache)
            }
            if (issue != null) {
                inconsistentList.add(InconsistentItem(song.id, "${song.artist} - ${song.title}", issue, uri, song))
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

    private suspend fun scanOrphanedSafEntries(
        cachedSongs: List<SongEntity>,
        safEntries: List<SafChildEntry>,
        inconsistentList: MutableList<InconsistentItem>
    ) {
        val validSafUris = cachedSongs.mapNotNull { it.localUri }.toSet()
        val lyricNames = safEntries.filter { it.uri.toString() in validSafUris }
            .mapTo(hashSetOf()) { FormatUtils.replaceExtensionWithLrc(it.name) }
        val unknownEntries = safEntries.filter { entry ->
            val uriStr = entry.uri.toString()
            entry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR && uriStr !in validSafUris && entry.name !in lyricNames
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
