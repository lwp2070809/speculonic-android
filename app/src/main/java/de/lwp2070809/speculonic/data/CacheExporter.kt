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
import java.io.File

@OptIn(UnstableApi::class)
object CacheExporter {

    private var cachedRootDoc: Pair<String, DocumentFile>? = null

    @Synchronized
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
    ): Result<String> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() } ?: return@withContext Result.failure(Exception("SAF未配置"))
        
        val cache = cacheDataSourceFactory.cache ?: return@withContext Result.failure(Exception("缓存实例缺失"))
        val cachedSpans = cache.getCachedSpans(song.id)
        if (cachedSpans.isEmpty()) {
            LogManager.i("CacheExporter: Song ${song.id} has no cached spans. Skipping export.")
            return@withContext Result.failure(Exception("缓存文件不完整"))
        }

        var contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(song.id))
        if (contentLength <= 0) {
            contentLength = cachedSpans.sumOf { it.length }
        }

        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(Exception("无法获取文件长度"))
        }

        try {
            val rootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
            if (rootDoc == null) {
                LogManager.e("CacheExporter: Failed to access SAF directory: $targetSafUriString")
                return@withContext Result.failure(Exception("无法访问目标文件夹"))
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
                return@withContext Result.failure(Exception("无法创建目标文件"))
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
            return@withContext Result.success(docFile.uri.toString())
        } catch (e: Exception) {
            LogManager.e("CacheExporter: Export failed for ${song.id}", e)
            Result.failure(e)
        }
    }

    suspend fun exportToPrivate(
        context: Context,
        song: Song,
        lyrics: String? = null,
        coverArtBytes: ByteArray? = null,
        cacheDataSourceFactory: CacheDataSource.Factory
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = cacheDataSourceFactory.cache ?: return@withContext Result.failure(Exception("缓存实例缺失"))
        val cachedSpans = cache.getCachedSpans(song.id)
        if (cachedSpans.isEmpty()) {
            LogManager.i("CacheExporter: Song ${song.id} has no cached spans. Skipping private export.")
            return@withContext Result.failure(Exception("缓存文件不完整"))
        }

        var contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(song.id))
        if (contentLength <= 0) {
            contentLength = cachedSpans.sumOf { it.length }
        }

        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(Exception("无法获取文件长度"))
        }

        try {
            val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            if (!privateDir.exists()) {
                privateDir.mkdirs()
            }

            val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeArtist = (song.artist ?: "Unknown Artist").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val suffix = if (song.suffix.isNullOrBlank()) "mp3" else song.suffix.lowercase()
            val finalFileName = "$safeArtist - $safeTitle [${song.id}].$suffix"
            val targetFile = File(privateDir, finalFileName)

            LogManager.d("CacheExporter: Streaming ${song.id} directly to private storage: ${targetFile.absolutePath}")

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

            targetFile.outputStream().use { outputStream ->
                val inputStream = DataSourceInputStream(cacheOnlyDataSource, dataSpec)
                inputStream.use { input ->
                    input.copyTo(outputStream)
                }
            }

            if (!lyrics.isNullOrBlank()) {
                val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(finalFileName)
                val lrcFile = File(privateDir, lrcFileName)
                lrcFile.writeText(lyrics)
            }

            val uriString = targetFile.toURI().toString()
            LogManager.i("CacheExporter: Private export complete for ${song.id}: $uriString")
            return@withContext Result.success(uriString)
        } catch (e: Exception) {
            LogManager.e("CacheExporter: Private export failed for ${song.id}", e)
            Result.failure(e)
        }
    }

    suspend fun exportPrivateFileToSaf(
        context: Context,
        song: Song,
        privateFileUriString: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() } ?: return@withContext Result.failure(Exception("SAF未配置"))

        try {
            val rootDoc = getCachedOrCreateRootDoc(context, targetSafUriString) ?: return@withContext Result.failure(Exception("无法访问目标文件夹"))
            val sourceUri = Uri.parse(privateFileUriString)
            val sourceFile = File(sourceUri.path ?: throw Exception("Invalid private file path"))
            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception("私有源文件不存在"))
            }

            val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val safeArtist = (song.artist ?: "Unknown Artist").replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val suffix = if (song.suffix.isNullOrBlank()) "mp3" else song.suffix.lowercase()
            val finalFileName = "$safeArtist - $safeTitle [${song.id}].$suffix"

            val existingFile = rootDoc.findFile(finalFileName)
            
            val docFile = if (existingFile != null && existingFile.exists()) {
                if (existingFile.length() == sourceFile.length()) {
                    LogManager.i("CacheExporter: File already exists in SAF with matching size, skipping stream write: $finalFileName")
                    existingFile
                } else {
                    existingFile.delete()
                    val mimeType = de.lwp2070809.speculonic.util.FormatUtils.getMimeTypeFromExtension(suffix)
                    rootDoc.createFile(mimeType, finalFileName)
                }
            } else {
                val mimeType = de.lwp2070809.speculonic.util.FormatUtils.getMimeTypeFromExtension(suffix)
                rootDoc.createFile(mimeType, finalFileName)
            }

            if (docFile == null) {
                return@withContext Result.failure(Exception("无法创建目标文件"))
            }

            if (existingFile == null || !existingFile.exists() || existingFile.length() != sourceFile.length()) {
                context.contentResolver.openOutputStream(docFile.uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: throw Exception("Failed to open SAF output stream")
            }

            val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(finalFileName)
            val sourceLrcFile = File(de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(sourceFile.absolutePath))
            if (sourceLrcFile.exists()) {
                val existingLrc = rootDoc.findFile(lrcFileName)
                val lrcFile = if (existingLrc != null && existingLrc.exists()) {
                    existingLrc
                } else {
                    rootDoc.createFile("application/octet-stream", lrcFileName)
                }
                
                lrcFile?.let { file ->
                    context.contentResolver.openOutputStream(file.uri)?.use { out ->
                        sourceLrcFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }
            }

            LogManager.i("CacheExporter: Exported private file to SAF: ${docFile.uri}")
            return@withContext Result.success(docFile.uri.toString())
        } catch (e: Exception) {
            LogManager.e("CacheExporter: Failed to export private file to SAF for ${song.id}", e)
            Result.failure(e)
        }
    }
}
