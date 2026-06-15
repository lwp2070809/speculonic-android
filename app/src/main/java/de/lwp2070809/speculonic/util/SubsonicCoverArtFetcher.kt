package de.lwp2070809.speculonic.util

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import de.lwp2070809.speculonic.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer


class SubsonicCoverArtFetcher(
    private val data: String,
    private val options: Options,
    private val context: Context,
    private val imageLoader: ImageLoader
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val uriString = data
        if (!uriString.contains("/rest/getCoverArt")) return null

        val id = data.toUri().getQueryParameter("id") ?: return null
        
        
        val diskCacheKey = options.diskCacheKey
        if (diskCacheKey != null) {
            val snapshot = imageLoader.diskCache?.openSnapshot(diskCacheKey)
            if (snapshot != null) {
                snapshot.close()
                return null 
            }
        }

        
        val localBytes = extractFromId3(id)
        if (localBytes != null) {
            LogManager.d("SubsonicCoverArtFetcher: 成功从本地已下载曲目的 ID3 中提取封面 (ID: $id)")
            val buffer = Buffer().apply { write(localBytes) }
            return SourceFetchResult(
                source = ImageSource(buffer, options.fileSystem),
                mimeType = null,
                dataSource = DataSource.MEMORY
            )
        }

        
        LogManager.d("SubsonicCoverArtFetcher: 缓存和本地 ID3 均未命中，准备由网络下载器发起远程封面请求 (ID: $id)")
        return null
    }

    private suspend fun extractFromId3(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            val localUri = db.musicDao().findLocalUriByCoverArtId(id)?.toUri() ?: return@withContext null
            
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, localUri)
                return@withContext retriever.embeddedPicture
            } catch (e: Exception) {
                LogManager.w("SubsonicCoverArtFetcher: Failed to extract picture from $localUri: ${e.message}")
                null
            } finally {
                try {
                    retriever.close()
                } catch (e: Exception) {
                    
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            val dataString = data.toString()
            return if (dataString.contains("/rest/getCoverArt")) {
                SubsonicCoverArtFetcher(dataString, options, context, imageLoader)
            } else {
                null
            }
        }
    }
}
