package de.lwp2070809.speculonic.data

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.db.entities.SongCleanupTarget
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全库同步确认删歌后的物理缓存清理。
 * 仅清理应用私有导出目录及其伴生歌词和 Media3 内部缓存区块，
 * 绝不在服务端元数据同步时删除用户外部 SAF 目录中的文件。
 */
@OptIn(UnstableApi::class)
@Singleton
class CacheCleanupHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheManager: CacheManager,
    private val downloadManagerHelper: DownloadManagerHelper,
    private val downloadTracker: DownloadTracker
) {

    suspend fun purgeRemovedSongs(targets: List<SongCleanupTarget>) {
        if (targets.isEmpty()) return

        val managerRemovalIds = withContext(Dispatchers.IO) {
            val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            val privateCanonicalPath = runCatching { privateDir.canonicalPath }.getOrNull()
            val downloadCache = runCatching { cacheManager.acquireDownloadCache() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            val playbackCache = runCatching { cacheManager.acquirePlaybackCache() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()

            val downloadIds = downloadTracker.allDownloadsFlow.value.mapTo(HashSet()) { it.task.request.id }
            var removedPrivateFiles = 0
            val activeIds = mutableListOf<String>()

            targets.forEach { target ->
                if (deletePrivateSongFile(target.localUri, privateCanonicalPath)) {
                    removedPrivateFiles++
                }
                if (!cacheManager.isReleasing) {
                    if (target.id in downloadIds) {
                        activeIds.add(target.id)
                    }
                    runCatching { downloadCache?.removeResource(target.id) }
                    runCatching { playbackCache?.let { PlaybackBuffer.remove(it, target.id) } }
                }
            }

            if (removedPrivateFiles > 0) {
                LogManager.i(
                    "CacheCleanupHelper: Purged $removedPrivateFiles private file(s) across ${targets.size} removed songs"
                )
            }
            activeIds
        }

        if (managerRemovalIds.isNotEmpty() && !cacheManager.isReleasing) {
            val downloadManager = runCatching { downloadManagerHelper.getDownloadManagerSuspend() }.getOrNull()
            if (downloadManager != null) {
                withContext(Dispatchers.Main.immediate) {
                    managerRemovalIds.forEach { songId ->
                        runCatching { downloadManager.removeDownload(songId) }
                    }
                }
            }
        }
    }

    private fun deletePrivateSongFile(localUri: String?, privateCanonicalPath: String?): Boolean {
        if (localUri.isNullOrBlank() || privateCanonicalPath == null) return false
        if (!localUri.startsWith("file:") && !localUri.startsWith("/")) return false
        return runCatching {
            val path = if (localUri.startsWith("file:")) localUri.toUri().path else localUri
            if (path.isNullOrBlank()) return false
            val file = File(path).canonicalFile
            if (!file.path.startsWith(privateCanonicalPath + File.separator)) return false
            val deletedAudio = if (file.exists()) file.delete() else false
            val lrcFile = File(FormatUtils.replaceExtensionWithLrc(file.absolutePath))
            if (lrcFile.exists()) {
                lrcFile.delete()
            }
            deletedAudio
        }.getOrElse { e ->
            LogManager.w("CacheCleanupHelper: Failed removing private audio file for uri=$localUri: ${e.message}")
            false
        }
    }
}
