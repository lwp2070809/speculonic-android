package de.lwp2070809.speculonic.domain.usecase

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ResolveInconsistencyUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SubsonicRepository
) {
    suspend operator fun invoke(item: InconsistentItem, action: Action): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val musicDao = db.musicDao()

            val deletePhysicalFile: (String) -> Boolean = { uriString ->
                try {
                    val uri = android.net.Uri.parse(uriString)
                    if (uri.scheme == "file") {
                        val path = uri.path
                        path != null && java.io.File(path).delete()
                    } else {
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete() == true
                    }
                } catch (e: Exception) {
                    LogManager.e("ResolveInconsistencyUseCase: Failed to delete physical file: $uriString", e)
                    false
                }
            }

            when (action) {
                Action.DELETE -> {
                    if (item.type == InconsistentItem.Type.ORPHANED_FILE) {
                        try {
                            item.localUri?.let { uri ->
                                deletePhysicalFile(uri)
                            }
                        } catch (e: Exception) { LogManager.e("ResolveInconsistencyUseCase: Failed to delete orphaned file", e) }
                    } else {
                        item.dbSong?.let { song ->
                            musicDao.updateSongCacheStatus(song.id, null, false)
                            try {
                                item.localUri?.let { uri ->
                                    deletePhysicalFile(uri)
                                }
                            } catch (e: Exception) {
                                LogManager.w("ResolveInconsistencyUseCase: Failed to delete DB song file", e)
                            }
                        }
                    }
                }
                Action.REDOWNLOAD -> {
                    item.dbSong?.let { song ->
                        musicDao.updateSongCacheStatus(song.id, null, false)
                        try {
                            item.localUri?.let { uri ->
                                deletePhysicalFile(uri)
                            }
                        } catch (e: Exception) {
                            LogManager.w("ResolveInconsistencyUseCase: Failed to delete DB song file before redownload", e)
                        }

                        val downloadController = DownloadController(context, repository)

                        val songModel = de.lwp2070809.speculonic.network.model.Song(
                            id = song.id,
                            title = song.title,
                            album = song.album,
                            artist = song.artist,
                            albumId = song.albumId,
                            duration = song.duration,
                            coverArt = song.coverArt,
                            path = song.path,
                            size = song.size,
                            md5 = song.md5
                        )
                        downloadController.downloadSong(songModel, isSilent = false)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LogManager.e("ResolveInconsistencyUseCase: Failed to resolve item", e)
            Result.failure(e)
        }
    }

    enum class Action {
        DELETE,
        REDOWNLOAD
    }
}
