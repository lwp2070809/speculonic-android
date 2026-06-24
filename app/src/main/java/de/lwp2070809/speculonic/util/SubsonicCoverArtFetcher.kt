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
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer


class SubsonicCoverArtFetcher(
    private val data: String,
    private val options: Options,
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val musicDao: MusicDao
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
            LogManager.d("SubsonicCoverArtFetcher: Successfully extracted cover art from local downloaded track ID3 (ID: $id)")
            val buffer = Buffer().apply { write(localBytes) }
            return SourceFetchResult(
                source = ImageSource(buffer, options.fileSystem),
                mimeType = null,
                dataSource = DataSource.MEMORY
            )
        }

        
        LogManager.d("SubsonicCoverArtFetcher: Both cache and local ID3 missed, preparing remote cover request via network fetcher (ID: $id)")
        return null
    }

    private suspend fun extractFromId3(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val localUri = musicDao.findLocalUriByCoverArtId(id)?.toUri() ?: return@withContext null
            
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

    class Factory(private val context: Context, private val musicDao: MusicDao) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            val dataString = data.toString()
            return if (dataString.contains("/rest/getCoverArt")) {
                SubsonicCoverArtFetcher(dataString, options, context, imageLoader, musicDao)
            } else {
                null
            }
        }
    }
}
