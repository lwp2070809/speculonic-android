package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
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
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
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
    private val downloadController: DownloadController
) {
    suspend operator fun invoke(item: InconsistentItem, action: Action): Result<Unit> =
        resolve(item, action, CacheValidator(context))

    suspend fun resolveAll(
        items: List<InconsistentItem>,
        action: Action,
        onProgress: (Int) -> Unit,
        onResult: (InconsistentItem, Result<Unit>) -> Unit
    ) {
        val validator = CacheValidator(context)
        items.forEachIndexed { index, item ->
            onProgress(index + 1)
            onResult(item, resolve(item, action, validator))
        }
    }

    private suspend fun resolve(item: InconsistentItem, action: Action, validator: CacheValidator): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                require(action != Action.REDOWNLOAD || item.canRedownload)
                if (item.type == InconsistentItem.Type.ORPHANED_FILE) {
                    deleteOrphan(item)
                } else {
                    resolveSong(item, action, validator)
                }
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
        deleteFile(uri)
    }

    private suspend fun resolveSong(item: InconsistentItem, action: Action, validator: CacheValidator) {
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
            if (uri != null && issue == InconsistentItem.Type.BINARY_MISMATCH) deleteFile(uri)
            dao.updateSongCacheStatus(song.id, null, false, false)
            if (action == Action.REDOWNLOAD) downloadController.enqueueReplacementDownload(EntityMapper.toSong(current))
        }
    }

    private fun deleteFile(uriString: String) {
        val uri = uriString.toUri()
        if (uri.scheme == "file") {
            Files.deleteIfExists(File(requireNotNull(uri.path)).toPath())
        } else if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            throw IOException(context.getString(R.string.cache_delete_failed))
        }
    }

    enum class Action {
        DELETE,
        REDOWNLOAD
    }
}
