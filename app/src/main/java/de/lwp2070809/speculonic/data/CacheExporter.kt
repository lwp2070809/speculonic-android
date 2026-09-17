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

    @Volatile
    private var cachedRootDoc: Pair<String, DocumentFile>? = null

    private fun getCachedOrCreateRootDoc(context: Context, targetSafUriString: String): DocumentFile? {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == targetSafUriString && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) {
            synchronized(this) {
                cachedRootDoc = null
            }
            LogManager.w("CacheExporter: SAF permissions revoked for $targetSafUriString")
            throw SecurityException("SAF_PERMISSION_EXPIRED")
        }
        val cached = cachedRootDoc
        if (cached != null && cached.first == targetSafUriString) {
            return cached.second
        }
        synchronized(this) {
            val secondCheck = cachedRootDoc
            if (secondCheck != null && secondCheck.first == targetSafUriString) {
                return secondCheck.second
            }
            val targetUri = targetSafUriString.toUri()
            return DocumentFile.fromTreeUri(context, targetUri)?.also {
                cachedRootDoc = targetSafUriString to it
            }
        }
    }

    fun invalidateCache() {
        synchronized(this) {
            cachedRootDoc = null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun resolveExtension(
        song: Song,
        targetTranscodeFormat: String?,
        defaultTranscodeFormat: String?
    ): String {
        return if (song.isTranscoded) {
            targetTranscodeFormat?.lowercase() ?: defaultTranscodeFormat?.lowercase() ?: "mp3"
        } else {
            if (song.suffix.isNullOrBlank()) "mp3" else song.suffix.lowercase()
        }
    }

    private fun buildExportFileName(
        song: Song,
        extension: String
    ): String {
        val safeTitle = sanitizeFileName(song.title)
        val safeArtist = sanitizeFileName(song.artist ?: "Unknown Artist")
        return "$safeArtist - $safeTitle [${song.id}].$extension"
    }

    private fun getCacheContentLength(
        cache: androidx.media3.datasource.cache.Cache,
        songId: String
    ): Long {
        val cachedSpans = cache.getCachedSpans(songId)
        if (cachedSpans.isEmpty()) return -1L
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(songId))
        return if (len > 0) len else cachedSpans.sumOf { it.length }
    }

    private fun streamCacheToOutput(
        cache: androidx.media3.datasource.cache.Cache,
        songId: String,
        contentLength: Long,
        outputStream: java.io.OutputStream
    ) {
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
            .setKey(songId)
            .build()

        DataSourceInputStream(cacheOnlyDataSource, dataSpec).use { input ->
            input.copyTo(outputStream)
        }
    }

    private fun writeLyricsToSaf(
        context: Context,
        rootDoc: DocumentFile,
        finalAudioFileName: String,
        lyrics: String
    ) {
        val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(finalAudioFileName)
        val existingLrc = rootDoc.findFile(lrcFileName)
        val lrcFile = existingLrc ?: rootDoc.createFile("application/octet-stream", lrcFileName)
        lrcFile?.let {
            context.contentResolver.openOutputStream(it.uri)?.use { out ->
                out.write(lyrics.toByteArray())
            }
        }
    }

    suspend fun exportToSaf(
        context: Context,
        song: Song,
        lyrics: String? = null,
        coverArtBytes: ByteArray? = null,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(Exception("SAF未配置"))

        val cache = cacheDataSourceFactory.cache ?: return@withContext Result.failure(Exception("缓存实例缺失"))
        val contentLength = getCacheContentLength(cache, song.id)
        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(Exception("缓存文件不完整"))
        }

        var docFile: DocumentFile? = null
        try {
            val rootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
                ?: return@withContext Result.failure(Exception("无法访问目标文件夹"))

            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val finalFileName = buildExportFileName(song, suffix)

            val existingFile = rootDoc.findFile(finalFileName)
            val mimeType = de.lwp2070809.speculonic.util.FormatUtils.getMimeTypeFromExtension(suffix)
            docFile = existingFile ?: rootDoc.createFile(mimeType, finalFileName)

            if (docFile == null) {
                LogManager.e("CacheExporter: Could not create file in SAF: $finalFileName")
                return@withContext Result.failure(Exception("无法创建目标文件"))
            }

            LogManager.d("CacheExporter: Streaming ${song.id} directly to SAF: ${docFile.uri}")
            context.contentResolver.openOutputStream(docFile.uri)?.use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            } ?: throw Exception("Failed to open SAF output stream")

            if (!lyrics.isNullOrBlank()) {
                writeLyricsToSaf(context, rootDoc, finalFileName, lyrics)
            }

            LogManager.i("CacheExporter: Bit-perfect export complete for ${song.id}")
            Result.success(docFile.uri.toString())
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                LogManager.e("CacheExporter: Export failed for ${song.id}", e)
            }
            try {
                if (docFile != null && docFile.exists()) {
                    docFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written SAF file", cleanupEx)
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun exportToPrivate(
        context: Context,
        song: Song,
        lyrics: String? = null,
        coverArtBytes: ByteArray? = null,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = cacheDataSourceFactory.cache ?: return@withContext Result.failure(Exception("缓存实例缺失"))
        val contentLength = getCacheContentLength(cache, song.id)
        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(Exception("无法获取文件长度"))
        }

        var targetFile: File? = null
        try {
            val privateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            if (!privateDir.exists()) {
                privateDir.mkdirs()
            }

            val preferencesManager = PreferencesManager.getInstance(context)
            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val finalFileName = buildExportFileName(song, suffix)
            targetFile = File(privateDir, finalFileName)

            LogManager.d("CacheExporter: Streaming ${song.id} directly to private storage: ${targetFile.absolutePath}")

            targetFile.outputStream().use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            }

            if (!lyrics.isNullOrBlank()) {
                val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(finalFileName)
                val lrcFile = File(privateDir, lrcFileName)
                lrcFile.writeText(lyrics)
            }

            val uriString = targetFile.toURI().toString()
            LogManager.i("CacheExporter: Private export complete for ${song.id}: $uriString")
            Result.success(uriString)
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                LogManager.e("CacheExporter: Private export failed for ${song.id}", e)
            }
            try {
                if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written private file", cleanupEx)
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    suspend fun exportPrivateFileToSaf(
        context: Context,
        song: Song,
        privateFileUriString: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(Exception("SAF未配置"))

        var docFile: DocumentFile? = null
        try {
            val rootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
                ?: return@withContext Result.failure(Exception("无法访问目标文件夹"))
            val sourceUri = Uri.parse(privateFileUriString)
            val sourceFile = File(sourceUri.path ?: throw Exception("Invalid private file path"))
            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception("私有源文件不存在"))
            }

            val suffix = if (sourceFile.extension.isNotBlank()) {
                sourceFile.extension.lowercase()
            } else {
                resolveExtension(song, null, preferencesManager.targetTranscodeFormat.first())
            }
            val finalFileName = buildExportFileName(song, suffix)

            val existingFile = rootDoc.findFile(finalFileName)
            
            docFile = if (existingFile != null && existingFile.exists()) {
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
            Result.success(docFile.uri.toString())
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) {
                LogManager.e("CacheExporter: Failed to export private file to SAF for ${song.id}", e)
            }
            try {
                if (docFile != null && docFile.exists()) {
                    docFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written SAF file", cleanupEx)
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }
}
