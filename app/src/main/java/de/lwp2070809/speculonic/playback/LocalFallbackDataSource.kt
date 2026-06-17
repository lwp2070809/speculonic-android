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
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.util.LogManager

@OptIn(UnstableApi::class)
class LocalFallbackDataSource(
    private val context: Context,
    private val upstream: DataSource
) : DataSource {

    private val contentDataSource = BufferedDataSource(ContentDataSource(context), 512 * 1024)
    private val fileDataSource = BufferedDataSource(FileDataSource(), 512 * 1024)
    private var activeDataSource: DataSource? = null
    private val db = AppDatabase.getDatabase(context)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        contentDataSource.addTransferListener(transferListener)
        fileDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val songId = dataSpec.key
        
        if (songId != null) {
            val localUri = queryLocalUri(songId)

            if (!localUri.isNullOrBlank()) {
                try {
                    val uri = localUri.toUri()
                    val targetDataSpec = dataSpec.withUri(uri)
                    val dataSource = if (localUri.startsWith("content://")) {
                        contentDataSource
                    } else {
                        fileDataSource
                    }
                    val bytesRead = dataSource.open(targetDataSpec)
                    activeDataSource = dataSource
                    LogManager.d("LocalFallbackDataSource: Successfully opened local file for $songId: $localUri")
                    return bytesRead
                } catch (e: Exception) {
                    LogManager.w("LocalFallbackDataSource: Failed to open local file for $songId, falling back to network. Error: ${e.message}")
                }
            }
        }

        activeDataSource = upstream
        return upstream.open(dataSpec)
    }

    private fun queryLocalUri(songId: String): String? {
        return try {
            val cursor = db.openHelper.readableDatabase.query(
                "SELECT localUri FROM songs WHERE id = ? AND isFullyCached = 1 LIMIT 1",
                arrayOf(songId)
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            LogManager.e("LocalFallbackDataSource: DB query failed for $songId", e)
            null
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return activeDataSource?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): Uri? {
        return activeDataSource?.getUri()
    }

    override fun close() {
        activeDataSource?.close()
        activeDataSource = null
    }
}
