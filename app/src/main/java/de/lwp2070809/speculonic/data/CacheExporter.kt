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
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

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
        val suffix = "[${song.id}].$extension"
        val lrcSuffix = "[${song.id}].lrc"
        // 同步考虑同源 .lrc 歌词文件，确保音频和伴生歌词文件名均不超过 255 UTF-8 字节上限
        val maxSuffixBytes = maxOf(
            " $suffix".encodeToByteArray().size,
            " $lrcSuffix".encodeToByteArray().size
        )
        val maxPrefixBytes = (255 - maxSuffixBytes).coerceAtLeast(0)
        val prefix = FormatUtils.truncateUtf8("$safeArtist - $safeTitle", maxPrefixBytes).trimEnd()
        return if (prefix.isEmpty()) {
            FormatUtils.truncateUtf8(suffix, 255)
        } else {
            "$prefix $suffix"
        }
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

    internal fun openTruncatedOutputStream(context: Context, uri: Uri): OutputStream? {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            LogManager.w("CacheExporter: DocumentProvider rejected mode 'wt', falling back to 'w': ${e.message}")
            context.contentResolver.openOutputStream(uri, "w")
        } catch (e: UnsupportedOperationException) {
            LogManager.w("CacheExporter: DocumentProvider unsupported mode 'wt', falling back to 'w': ${e.message}")
            context.contentResolver.openOutputStream(uri, "w")
        }
    }

    private fun writeLyricsToSaf(
        context: Context,
        rootDoc: DocumentFile,
        finalAudioFileName: String,
        lyrics: String,
        existingLrcUri: Uri? = null
    ) {
        val lrcFileName = FormatUtils.replaceExtensionWithLrc(finalAudioFileName)
        val targetLrcUri = existingLrcUri ?: rootDoc.createFile("application/octet-stream", lrcFileName)?.uri
        targetLrcUri?.let { uri ->
            openTruncatedOutputStream(context, uri)?.use { out ->
                out.write(lyrics.toByteArray())
            }
        }
    }

    suspend fun exportToSaf(
        context: Context,
        song: Song,
        lyrics: String? = null,
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

        val targetSafUri = targetSafUriString.toUri()
        var cleanupDocFile: DocumentFile? = null
        var existingLrcUri: Uri? = null
        val (rootDoc, docUri, finalFileName) = try {
            val resolvedRootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
                ?: return@withContext Result.failure(Exception("无法访问目标文件夹"))

            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(resolvedFileName)
            val preScanned = SafDirectoryHelper.findChildrenByName(
                context = context,
                treeUri = targetSafUri,
                targetNames = setOf(resolvedFileName, lrcFileName)
            )
            existingLrcUri = preScanned[lrcFileName]?.uri

            val existingAudio = preScanned[resolvedFileName]
            val targetDocUri = if (existingAudio != null) {
                existingAudio.uri
            } else {
                val mimeType = FormatUtils.getMimeTypeFromExtension(suffix)
                val createdDoc = resolvedRootDoc.createFile(mimeType, resolvedFileName)
                    ?: run {
                        LogManager.e("CacheExporter: Could not create file in SAF: $resolvedFileName")
                        return@withContext Result.failure(Exception("无法创建目标文件"))
                    }
                cleanupDocFile = createdDoc
                createdDoc.uri
            }

            LogManager.d("CacheExporter: Streaming ${song.id} directly to SAF: $targetDocUri")
            openTruncatedOutputStream(context, targetDocUri)?.use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            } ?: throw Exception("Failed to open SAF output stream")
            Triple(resolvedRootDoc, targetDocUri, resolvedFileName)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Export failed for ${song.id}", e)
            }
            try {
                if (cleanupDocFile != null && cleanupDocFile.exists()) {
                    cleanupDocFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written SAF file", cleanupEx)
            }
            if (e is CancellationException) throw e
            return@withContext Result.failure(e)
        }

        if (!lyrics.isNullOrBlank()) {
            try {
                writeLyricsToSaf(context, rootDoc, finalFileName, lyrics, existingLrcUri = existingLrcUri)
            } catch (lrcEx: Exception) {
                if (lrcEx is CancellationException) throw lrcEx
                LogManager.w(
                    "CacheExporter: Audio exported cleanly, but companion .lrc write failed for ${song.id}: ${lrcEx.message}"
                )
            }
        }

        LogManager.i("CacheExporter: Bit-perfect export complete for ${song.id}")
        Result.success(docUri.toString())
    }

    suspend fun exportToPrivate(
        context: Context,
        song: Song,
        lyrics: String? = null,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = cacheDataSourceFactory.cache ?: return@withContext Result.failure(Exception("缓存实例缺失"))
        val contentLength = getCacheContentLength(cache, song.id)
        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(Exception("无法获取文件长度"))
        }

        var cleanupFile: File? = null
        val (privateDir, targetFile, finalFileName) = try {
            val resolvedPrivateDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
            if (!resolvedPrivateDir.exists()) {
                resolvedPrivateDir.mkdirs()
            }

            val preferencesManager = PreferencesManager.getInstance(context)
            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val resolvedTargetFile = File(resolvedPrivateDir, resolvedFileName)
            cleanupFile = resolvedTargetFile

            LogManager.d("CacheExporter: Streaming ${song.id} directly to private storage: ${resolvedTargetFile.absolutePath}")

            resolvedTargetFile.outputStream().use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            }
            Triple(resolvedPrivateDir, resolvedTargetFile, resolvedFileName)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Private export failed for ${song.id}", e)
            }
            try {
                if (cleanupFile != null && cleanupFile.exists()) {
                    cleanupFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written private file", cleanupEx)
            }
            if (e is CancellationException) throw e
            return@withContext Result.failure(e)
        }

        if (!lyrics.isNullOrBlank()) {
            try {
                val lrcFileName = FormatUtils.replaceExtensionWithLrc(finalFileName)
                File(privateDir, lrcFileName).writeText(lyrics)
            } catch (lrcEx: Exception) {
                if (lrcEx is CancellationException) throw lrcEx
                LogManager.w(
                    "CacheExporter: Private audio exported cleanly, but .lrc write failed for ${song.id}: ${lrcEx.message}"
                )
            }
        }

        val uriString = targetFile.toURI().toString()
        LogManager.i("CacheExporter: Private export complete for ${song.id}: $uriString")
        Result.success(uriString)
    }

    suspend fun exportPrivateFileToSaf(
        context: Context,
        song: Song,
        privateFileUriString: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager.getInstance(context)
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(Exception("SAF未配置"))

        val sourcePath = privateFileUriString.toUri().path
            ?: return@withContext Result.failure(Exception("Invalid private file path"))
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return@withContext Result.failure(Exception("私有源文件不存在"))
        }

        val targetSafUri = targetSafUriString.toUri()
        var cleanupDocFile: DocumentFile? = null
        var existingLrcUri: Uri? = null
        val (rootDoc, docUri, finalFileName) = try {
            val resolvedRootDoc = getCachedOrCreateRootDoc(context, targetSafUriString)
                ?: return@withContext Result.failure(Exception("无法访问目标文件夹"))

            val suffix = sourceFile.extension.takeIf { it.isNotBlank() }?.lowercase()
                ?: resolveExtension(song, null, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(resolvedFileName)
            val preScanned = SafDirectoryHelper.findChildrenByName(
                context = context,
                treeUri = targetSafUri,
                targetNames = setOf(resolvedFileName, lrcFileName)
            )
            existingLrcUri = preScanned[lrcFileName]?.uri

            val existingAudio = preScanned[resolvedFileName]
            val targetDocUri = if (existingAudio != null && existingAudio.size == sourceFile.length()) {
                LogManager.i(
                    "CacheExporter: File already exists in SAF with matching size, skipping stream write: $resolvedFileName"
                )
                existingAudio.uri
            } else {
                if (existingAudio != null) {
                    DocumentFile.fromSingleUri(context, existingAudio.uri)?.delete()
                }
                val mimeType = FormatUtils.getMimeTypeFromExtension(suffix)
                val createdDoc = resolvedRootDoc.createFile(mimeType, resolvedFileName)
                    ?: return@withContext Result.failure(Exception("无法创建目标文件"))
                cleanupDocFile = createdDoc
                openTruncatedOutputStream(context, createdDoc.uri)?.use { outputStream ->
                    sourceFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
                } ?: throw Exception("Failed to open SAF output stream")
                createdDoc.uri
            }
            Triple(resolvedRootDoc, targetDocUri, resolvedFileName)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Failed to export private file to SAF for ${song.id}", e)
            }
            try {
                if (cleanupDocFile != null && cleanupDocFile.exists()) {
                    cleanupDocFile.delete()
                }
            } catch (cleanupEx: Exception) {
                LogManager.e("CacheExporter: Failed to clean up half-written SAF file", cleanupEx)
            }
            if (e is CancellationException) throw e
            return@withContext Result.failure(e)
        }

        val sourceLrcFile = File(FormatUtils.replaceExtensionWithLrc(sourceFile.absolutePath))
        if (sourceLrcFile.exists()) {
            try {
                val lrcFileName = FormatUtils.replaceExtensionWithLrc(finalFileName)
                val targetLrcUri = existingLrcUri ?: rootDoc.createFile("application/octet-stream", lrcFileName)?.uri
                targetLrcUri?.let { uri ->
                    openTruncatedOutputStream(context, uri)?.use { out ->
                        sourceLrcFile.inputStream().use { input -> input.copyTo(out) }
                    }
                }
            } catch (lrcEx: Exception) {
                if (lrcEx is CancellationException) throw lrcEx
                LogManager.w(
                    "CacheExporter: Private audio migrated cleanly, but .lrc export failed for ${song.id}: ${lrcEx.message}"
                )
            }
        }

        LogManager.i("CacheExporter: Exported private file to SAF: $docUri")
        Result.success(docUri.toString())
    }
}
