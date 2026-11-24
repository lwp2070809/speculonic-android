package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.EntityMapper
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ResolveInconsistencyUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: de.lwp2070809.speculonic.data.db.dao.MusicDao,
    private val downloadController: DownloadController
) {
    suspend operator fun invoke(item: InconsistentItem, action: Action): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                when (action) {
                    Action.DELETE -> resolveDelete(item)
                    Action.REDOWNLOAD -> resolveRedownload(item)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LogManager.e("ResolveInconsistencyUseCase: Failed to resolve item", e)
                Result.failure(e)
            }
        }

    private suspend fun resolveDelete(item: InconsistentItem) {
        if (item.type == InconsistentItem.Type.ORPHANED_FILE) {
            deletePhysicalFileSafely(item.localUri, "Failed to delete orphaned file")
            return
        }
        item.dbSong?.let { song ->
            musicDao.updateSongCacheStatus(song.id, null, false)
            deletePhysicalFileSafely(item.localUri, "Failed to delete DB song file")
        }
    }

    private suspend fun resolveRedownload(item: InconsistentItem) {
        val song = item.dbSong ?: return
        musicDao.updateSongCacheStatus(song.id, null, false)
        deletePhysicalFileSafely(item.localUri, "Failed to delete DB song file before redownload")
        downloadController.redownloadSong(EntityMapper.toSong(song), isSilent = false)
    }

    private fun deletePhysicalFileSafely(uriString: String?, errorMessage: String) {
        if (uriString == null) return
        try {
            val uri = uriString.toUri()
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) java.io.File(path).delete()
            } else {
                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LogManager.w("ResolveInconsistencyUseCase: $errorMessage ($uriString)", e)
        }
    }

    enum class Action {
        DELETE,
        REDOWNLOAD
    }
}
