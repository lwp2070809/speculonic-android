package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
object CacheExporter {

    @Volatile
    private var cachedRootDoc: Pair<String, DocumentFile>? = null

    
    private fun getCachedOrCreateRootDoc(context: Context, targetSafUriString: String): DocumentFile? {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == targetSafUriString && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) {
            cachedRootDoc = null
            LogManager.w("CacheExporter: SAF permissions revoked for $targetSafUriString")
            throw SecurityException("SAF_PERMISSION_EXPIRED")
        }
        val cached = cachedRootDoc
        if (cached != null && cached.first == targetSafUriString) {
            return cached.second
        }
        val targetUri = targetSafUriString.toUri()
        return DocumentFile.fromTreeUri(context, targetUri)?.also {
            cachedRootDoc = targetSafUriString to it
        }
    }

    
    fun invalidateCache() {
        cachedRootDoc = null
    }

    
    suspend fun exportToSaf(
        context: Context,
        song: Song,
        lyrics: String? = null,
        coverArtBytes: ByteArray? = null,
        cacheDataSourceFactory: CacheDataSource.Factory
    ): String? = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() } ?: return@withContext null
        
        val cache = cacheDataSourceFactory.cache ?: return@withContext null
        val cachedSpans = cache.getCachedSpans(song.id)
        if (cachedSpans.isEmpty()) {
            LogManager.i("CacheExporter: Song ${song.id} has no cached spans. Skipping export.")
            return@withContext null
        }

        var contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(song.id))
        if (contentLength <= 0) {
            contentLength = cachedSpans.sumOf { it.length }
        }

        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext null
        }

        try {
            
            val rootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
            
            if (rootDoc == null) {
                LogManager.e("CacheExporter: Failed to access SAF directory: $targetSafUriString")
                return@withContext null
            }
            
            val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeArtist = (song.artist ?: "Unknown Artist").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            
            val suffix = if (song.suffix.isNullOrBlank()) "mp3" else song.suffix.lowercase()
            val finalFileName = "$safeArtist - $safeTitle [${song.id}].$suffix"
            
            val existingFile = rootDoc.findFile(finalFileName)
            val mimeType = de.lwp2070809.speculonic.util.FormatUtils.getMimeTypeFromExtension(suffix)
            val docFile = existingFile ?: rootDoc.createFile(mimeType, finalFileName)
            
            if (docFile == null) {
                LogManager.e("CacheExporter: Could not create file in SAF: $finalFileName")
                return@withContext null
            }

            
            LogManager.d("CacheExporter: Streaming ${song.id} directly to SAF: ${docFile.uri}")
            
            val cacheOnlyDataSource = CacheDataSource(
                cache,
                null,
                FileDataSource(),
                null,
                CacheDataSource.FLAG_BLOCK_ON_CACHE,
                null
            )

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.EMPTY)
                .setPosition(0)
                .setLength(contentLength)
                .setKey(song.id)
                .build()

            context.contentResolver.openOutputStream(docFile.uri)?.use { outputStream ->
                val inputStream = DataSourceInputStream(cacheOnlyDataSource, dataSpec)
                inputStream.use { input ->
                    input.copyTo(outputStream)
                }
            } ?: throw Exception("Failed to open SAF output stream")

            
            if (!lyrics.isNullOrBlank()) {
                val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(finalFileName)
                val existingLrc = rootDoc.findFile(lrcFileName)
                val lrcFile = existingLrc ?: rootDoc.createFile("application/octet-stream", lrcFileName)
                lrcFile?.let {
                    context.contentResolver.openOutputStream(it.uri)?.use { out ->
                        out.write(lyrics.toByteArray())
                    }
                }
            }
            
            LogManager.i("CacheExporter: Bit-perfect export complete for ${song.id}")
            return@withContext docFile.uri.toString()
        } catch (e: Exception) {
            LogManager.e("CacheExporter: Export failed for ${song.id}", e)
            null
        }
    }
}
