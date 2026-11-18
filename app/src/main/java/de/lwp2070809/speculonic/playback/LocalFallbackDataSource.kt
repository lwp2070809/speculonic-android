package de.lwp2070809.speculonic.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag

@OptIn(UnstableApi::class)
class LocalFallbackDataSource(
    context: Context,
    private val upstream: DataSource,
    private val musicDao: MusicDao
) : DataSource {

    private val contentDataSource = BufferedDataSource(ContentDataSource(context))
    private val fileDataSource = BufferedDataSource(FileDataSource())
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        contentDataSource.addTransferListener(transferListener)
        fileDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val songId = dataSpec.key
        if (songId != null) {
            openLocalFileIfAvailable(songId, dataSpec)?.let { return it }
        }

        activeDataSource = upstream
        if (songId != null) {
            LogManager.i(
                LogTag.CACHE,
                "Playback source: Upstream cache/network for songId=$songId"
            )
        }
        return upstream.open(dataSpec)
    }

    private fun openLocalFileIfAvailable(songId: String, dataSpec: DataSpec): Long? {
        val localUri = queryLocalUri(songId)
        if (localUri.isNullOrBlank()) return null

        val isSaf = localUri.startsWith("content://")
        val dataSource = if (isSaf) contentDataSource else fileDataSource
        activeDataSource = dataSource
        return try {
            val bytesRead = dataSource.open(dataSpec.withUri(localUri.toUri()))
            val storageType = if (isSaf) "SAF" else "Private"
            LogManager.i(
                LogTag.CACHE,
                "Playback source: Matched $storageType file for songId=$songId: $localUri"
            )
            bytesRead
        } catch (e: Exception) {
            try {
                close()
            } catch (closeError: Exception) {
                LogManager.w(LogTag.CACHE, "Failed to close local file for songId=$songId", closeError)
            }
            LogManager.w(
                LogTag.CACHE,
                "Playback source: Failed to open local file for songId=$songId, falling back. Error: ${e.message}"
            )
            null
        }
    }

    private fun queryLocalUri(songId: String): String? {
        return try {
            musicDao.getSongByIdSync(songId)?.takeIf { it.isFullyCached }?.localUri
        } catch (e: Exception) {
            LogManager.e("LocalFallbackDataSource: DAO query failed for $songId", e)
            null
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDataSource?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): Uri? {
        return activeDataSource?.getUri()
    }

    override fun getResponseHeaders(): Map<String, List<String>> = activeDataSource?.responseHeaders.orEmpty()

    override fun close() {
        try {
            activeDataSource?.close()
        } finally {
            activeDataSource = null
        }
    }
}
