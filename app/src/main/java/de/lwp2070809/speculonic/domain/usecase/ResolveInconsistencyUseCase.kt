package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.CacheValidator
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.SafDirectoryHelper
import de.lwp2070809.speculonic.data.isRegularSafFileType
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.MediaFormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject

@OptIn(UnstableApi::class)
class ResolveInconsistencyUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val cacheManager: CacheManager,
    private val downloadTracker: DownloadTracker,
    private val downloadController: DownloadController,
    private val verifyCache: VerifyCacheConsistencyUseCase
) {
    suspend fun resolveAll(
        items: List<InconsistentItem>,
        action: Action,
        onProgress: (Int) -> Unit,
        onResult: (InconsistentItem, Result<Unit>) -> Unit
    ) {
        val validator = CacheValidator(context)
        items.forEachIndexed { index, item ->
            onProgress(index + 1)
            if (!action.canResolve(item)) {
                onResult(item, Result.failure(IllegalArgumentException(context.getString(R.string.cache_changed))))
                return@forEachIndexed
            }
            val cleanup = when (item.type) {
                InconsistentItem.Type.AWAITING_REDOWNLOAD -> Result.success(Unit)
                InconsistentItem.Type.ORPHANED_FILE -> runAction(item) { deleteOrphan(item) }
                else -> runAction(item) { clearSong(item, validator, action) }
            }
            if (action == Action.REDOWNLOAD && cleanup.isSuccess) {
                val pending = item.copy(type = InconsistentItem.Type.AWAITING_REDOWNLOAD, localUri = null)
                onResult(pending, runAction(pending) { enqueueCleanedSong(pending.id) })
            } else {
                onResult(item, cleanup)
            }
        }
    }

    private suspend fun runAction(item: InconsistentItem, operation: suspend () -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                operation()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e("ResolveInconsistencyUseCase: Failed to resolve ${item.id}", e)
                Result.failure(e)
            }
        }

    private suspend fun deleteOrphan(item: InconsistentItem) = database.withTransaction {
        val uri = requireNotNull(item.localUri)
        val dao = database.musicDao()
        val song = item.dbSong?.let { dao.getSongById(it.id) }
        check(song?.isFullyCached != true && !dao.hasSongAtUri(uri)) { context.getString(R.string.cache_changed) }
        if (item.displayTitle.substringAfterLast('.').equals(MediaFormatUtils.EXTENSION_LRC, ignoreCase = true)) {
            val entries = SafDirectoryHelper.listChildEntries(context, uri.toUri())
            val candidate = entries.singleOrNull { it.uri.toString() == uri }
            check(candidate != null && candidate.name == item.displayTitle) { context.getString(R.string.cache_changed) }
            val orphans = verifyCache.findOrphanedSafEntries(dao.getCachedFileUris().toSet(), entries, listOf(candidate))
            check(orphans.isNotEmpty()) { context.getString(R.string.cache_changed) }
        }
        deleteFile(uri)
    }

    private suspend fun clearSong(item: InconsistentItem, validator: CacheValidator, action: Action) {
        val dao = database.musicDao()
        val song = dao.getSongById(item.id)
        check(song != null && song.isFullyCached && song.localUri == item.localUri) {
            context.getString(R.string.cache_changed)
        }
        val uri = song.localUri
        val issue = if (uri != null) validator.inspectFile(uri.toUri(), song)
            else validator.inspectDownloadCache(song, cacheManager.acquireDownloadCache())
        check(issue != null) { context.getString(R.string.cache_changed) }

        downloadTracker.discardInconsistentDownload(song.id)
        database.withTransaction {
            val current = dao.getSongById(song.id)
            check(current?.isFullyCached == true && current.localUri == uri) { context.getString(R.string.cache_changed) }
            if (action == Action.DELETE && uri != null) {
                val lyric = findCompanionLyric(uri.toUri(), song.id)
                if (lyric != null && !dao.hasSongAtUri(lyric)) deleteFile(lyric)
            }
            if (uri != null && issue == InconsistentItem.Type.BINARY_MISMATCH) deleteFile(uri)
            dao.updateSongCacheStatus(song.id, null, false, false)
        }
    }

    private fun findCompanionLyric(audio: Uri, songId: String): String? {
        if (audio.scheme == "file") {
            val file = File(requireNotNull(audio.path))
            val lyric = File(FormatUtils.replaceExtensionWithLrc(file.path))
            if (!lyric.isFile) return null
            val siblings = file.parentFile?.listFiles() ?: throw IOException("Cannot list private audio directory")
            return lyric.toURI().toString().takeUnless {
                siblings.any { it != file && it.isFile && sharesLyric(it.name, lyric.name) }
            }
        }
        if (!DocumentsContract.isTreeUri(audio)) return null
        val entries = SafDirectoryHelper.listChildEntries(context, audio)
        val audioName = entries.firstOrNull { it.uri == audio }?.name
        val lyricName = audioName?.let(FormatUtils::replaceExtensionWithLrc)
        // A missing SAF document may have an opaque ID. Only the exported song ID can then identify its lyric.
        val lyric = entries.singleOrNull {
            isRegularSafFileType(it.mimeType) && it.name.endsWith(".lrc", ignoreCase = true) &&
                if (lyricName != null) it.name == lyricName else MediaFormatUtils.extractSongIdFromFileName(it.name) == songId
        } ?: return null
        return lyric.uri.toString().takeUnless {
            entries.any { it.uri != audio && isRegularSafFileType(it.mimeType) && sharesLyric(it.name, lyric.name) }
        }
    }

    private fun sharesLyric(audioName: String, lyricName: String): Boolean =
        MediaFormatUtils.isSupportedAudioFile(audioName) && FormatUtils.replaceExtensionWithLrc(audioName) == lyricName

    private suspend fun enqueueCleanedSong(id: String) {
        val current = database.musicDao().getSongById(id)
        check(current != null && !current.isFullyCached && current.localUri == null) { context.getString(R.string.cache_changed) }
        downloadController.enqueueReplacementDownload(EntityMapper.toSong(current))
    }

    private fun deleteFile(uriString: String) {
        val uri = uriString.toUri()
        if (uri.scheme == "file") {
            val path = File(requireNotNull(uri.path)).toPath()
            check(Files.isRegularFile(path)) { context.getString(R.string.cache_changed) }
            Files.delete(path)
        } else {
            check(CacheValidator(context).isRegularSafFile(uri)) { context.getString(R.string.cache_changed) }
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw IOException(context.getString(R.string.cache_delete_failed))
            }
        }
    }

    enum class Action {
        DELETE,
        REDOWNLOAD;

        fun canResolve(item: InconsistentItem) = when (this) {
            DELETE -> item.canDelete
            REDOWNLOAD -> item.canRedownload
        }
    }
}
