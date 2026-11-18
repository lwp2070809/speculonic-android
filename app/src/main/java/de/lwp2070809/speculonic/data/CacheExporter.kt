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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class CacheExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    @Volatile
    private var cachedRootDoc: Pair<String, DocumentFile>? = null

    private fun getCachedOrCreateRootDoc(targetSafUriString: String): DocumentFile? {
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
        val maxPrefixBytes = (MAX_FILENAME_UTF8_BYTES - maxSuffixBytes).coerceAtLeast(0)
        val prefix = FormatUtils.truncateUtf8("$safeArtist - $safeTitle", maxPrefixBytes).trimEnd()
        return if (prefix.isEmpty()) {
            FormatUtils.truncateUtf8(suffix, MAX_FILENAME_UTF8_BYTES)
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

    private fun writeLyricsToSaf(
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
        song: Song,
        lyrics: String? = null,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("SAF未配置"))

        val cache = cacheDataSourceFactory.cache
            ?: return@withContext Result.failure(IllegalStateException("缓存实例缺失"))
        val contentLength = getCacheContentLength(cache, song.id)
        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(IOException("缓存文件不完整"))
        }

        val outcome = streamAudioToSafTarget(
            song = song,
            cache = cache,
            contentLength = contentLength,
            targetSafUriString = targetSafUriString,
            targetTranscodeFormat = targetTranscodeFormat
        )
        outcome.map { exported ->
            writeOptionalSafLyrics(song.id, exported.rootDoc, exported.fileName, lyrics, exported.existingLrcUri)
            LogManager.i("CacheExporter: Bit-perfect export complete for ${song.id}")
            exported.docUri.toString()
        }
    }

    private data class SafExportedTarget(
        val rootDoc: DocumentFile,
        val docUri: Uri,
        val fileName: String,
        val existingLrcUri: Uri?
    )

    private suspend fun streamAudioToSafTarget(
        song: Song,
        cache: androidx.media3.datasource.cache.Cache,
        contentLength: Long,
        targetSafUriString: String,
        targetTranscodeFormat: String?
    ): Result<SafExportedTarget> {
        var cleanupDocFile: DocumentFile? = null
        return try {
            val resolvedRootDoc = getCachedOrCreateRootDoc(targetSafUriString)
                ?: return Result.failure(IOException("无法访问目标文件夹"))
            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(resolvedFileName)
            val preScanned = SafDirectoryHelper.findChildrenByName(
                context = context,
                treeUri = targetSafUriString.toUri(),
                targetNames = setOf(resolvedFileName, lrcFileName)
            )
            val targetDocUri = preScanned[resolvedFileName]?.uri ?: run {
                val createdDoc = resolvedRootDoc.createFile(
                    FormatUtils.getMimeTypeFromExtension(suffix),
                    resolvedFileName
                ) ?: run {
                    LogManager.e("CacheExporter: Could not create file in SAF: $resolvedFileName")
                    return Result.failure(IOException("无法创建目标文件"))
                }
                cleanupDocFile = createdDoc
                createdDoc.uri
            }
            LogManager.d("CacheExporter: Streaming ${song.id} directly to SAF: $targetDocUri")
            openTruncatedOutputStream(context, targetDocUri)?.use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            } ?: throw IOException("Failed to open SAF output stream")

            Result.success(
                SafExportedTarget(resolvedRootDoc, targetDocUri, resolvedFileName, preScanned[lrcFileName]?.uri)
            )
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Export failed for ${song.id}", e)
            }
            cleanupHalfWrittenDoc(cleanupDocFile)
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun writeOptionalSafLyrics(
        songId: String,
        rootDoc: DocumentFile,
        finalAudioFileName: String,
        lyrics: String?,
        existingLrcUri: Uri?
    ) {
        if (lyrics.isNullOrBlank()) return
        try {
            writeLyricsToSaf(rootDoc, finalAudioFileName, lyrics, existingLrcUri = existingLrcUri)
        } catch (lrcEx: Exception) {
            if (lrcEx is CancellationException) throw lrcEx
            LogManager.w(
                "CacheExporter: Audio exported cleanly, but companion .lrc write failed for $songId: ${lrcEx.message}"
            )
        }
    }

    suspend fun exportToPrivate(
        song: Song,
        lyrics: String? = null,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = cacheDataSourceFactory.cache
            ?: return@withContext Result.failure(IllegalStateException("缓存实例缺失"))
        val contentLength = getCacheContentLength(cache, song.id)
        if (contentLength <= 0) {
            LogManager.e("CacheExporter: Could not determine content length for ${song.id}")
            return@withContext Result.failure(IOException("无法获取文件长度"))
        }

        val outcome = streamAudioToPrivateTarget(song, cache, contentLength, targetTranscodeFormat)
        outcome.map { (privateDir, targetFile, finalFileName) ->
            writeOptionalPrivateLyrics(song.id, privateDir, finalFileName, lyrics)
            val uriString = targetFile.toURI().toString()
            LogManager.i("CacheExporter: Private export complete for ${song.id}: $uriString")
            uriString
        }
    }

    private suspend fun streamAudioToPrivateTarget(
        song: Song,
        cache: androidx.media3.datasource.cache.Cache,
        contentLength: Long,
        targetTranscodeFormat: String?
    ): Result<Triple<File, File, String>> {
        var cleanupFile: File? = null
        return try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val resolvedPrivateDir = File(baseDir, "media_exported_private")
            if (!resolvedPrivateDir.exists()) {
                resolvedPrivateDir.mkdirs()
            }
            val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val resolvedTargetFile = File(resolvedPrivateDir, resolvedFileName)
            cleanupFile = resolvedTargetFile
            LogManager.d(
                "CacheExporter: Streaming ${song.id} directly to private storage: ${resolvedTargetFile.absolutePath}"
            )
            resolvedTargetFile.outputStream().use { outputStream ->
                streamCacheToOutput(cache, song.id, contentLength, outputStream)
            }
            Result.success(Triple(resolvedPrivateDir, resolvedTargetFile, resolvedFileName))
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Private export failed for ${song.id}", e)
            }
            cleanupHalfWrittenFile(cleanupFile)
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun writeOptionalPrivateLyrics(
        songId: String,
        privateDir: File,
        finalFileName: String,
        lyrics: String?
    ) {
        if (lyrics.isNullOrBlank()) return
        try {
            File(privateDir, FormatUtils.replaceExtensionWithLrc(finalFileName)).writeText(lyrics)
        } catch (lrcEx: Exception) {
            if (lrcEx is CancellationException) throw lrcEx
            LogManager.w(
                "CacheExporter: Private audio exported cleanly, but .lrc write failed for $songId: ${lrcEx.message}"
            )
        }
    }

    suspend fun exportPrivateFileToSaf(
        song: Song,
        privateFileUriString: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("SAF未配置"))

        val sourcePath = privateFileUriString.toUri().path
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid private file path"))
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return@withContext Result.failure(IOException("私有源文件不存在"))
        }

        val outcome = copyPrivateAudioToSafTarget(song, sourceFile, targetSafUriString)
        outcome.map { exported ->
            copyOptionalPrivateLrcToSaf(song.id, sourceFile, exported)
            LogManager.i("CacheExporter: Exported private file to SAF: ${exported.docUri}")
            exported.docUri.toString()
        }
    }

    private suspend fun copyPrivateAudioToSafTarget(
        song: Song,
        sourceFile: File,
        targetSafUriString: String
    ): Result<SafExportedTarget> {
        var cleanupDocFile: DocumentFile? = null
        return try {
            val resolvedRootDoc = getCachedOrCreateRootDoc(targetSafUriString)
                ?: return Result.failure(IOException("无法访问目标文件夹"))
            val suffix = sourceFile.extension.takeIf { it.isNotBlank() }?.lowercase()
                ?: resolveExtension(song, null, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(resolvedFileName)
            val preScanned = SafDirectoryHelper.findChildrenByName(
                context = context,
                treeUri = targetSafUriString.toUri(),
                targetNames = setOf(resolvedFileName, lrcFileName)
            )
            val targetDocUri = resolveOrCreateSafAudioUri(
                rootDoc = resolvedRootDoc,
                existingAudio = preScanned[resolvedFileName],
                sourceFile = sourceFile,
                fileName = resolvedFileName,
                suffix = suffix,
                onDocCreated = { cleanupDocFile = it }
            ) ?: return Result.failure(IOException("无法创建目标文件"))

            Result.success(
                SafExportedTarget(resolvedRootDoc, targetDocUri, resolvedFileName, preScanned[lrcFileName]?.uri)
            )
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LogManager.e("CacheExporter: Failed to export private file to SAF for ${song.id}", e)
            }
            cleanupHalfWrittenDoc(cleanupDocFile)
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private fun resolveOrCreateSafAudioUri(
        rootDoc: DocumentFile,
        existingAudio: SafChildEntry?,
        sourceFile: File,
        fileName: String,
        suffix: String,
        onDocCreated: (DocumentFile) -> Unit
    ): Uri? {
        if (existingAudio != null && existingAudio.size == sourceFile.length()) {
            LogManager.i(
                "CacheExporter: File already exists in SAF with matching size, skipping stream write: $fileName"
            )
            return existingAudio.uri
        }
        if (existingAudio != null) {
            DocumentFile.fromSingleUri(context, existingAudio.uri)?.delete()
        }
        val createdDoc = rootDoc.createFile(FormatUtils.getMimeTypeFromExtension(suffix), fileName)
            ?: return null
        onDocCreated(createdDoc)
        openTruncatedOutputStream(context, createdDoc.uri)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
        } ?: throw IOException("Failed to open SAF output stream")
        return createdDoc.uri
    }

    private fun copyOptionalPrivateLrcToSaf(
        songId: String,
        sourceFile: File,
        exported: SafExportedTarget
    ) {
        val sourceLrcFile = File(FormatUtils.replaceExtensionWithLrc(sourceFile.absolutePath))
        if (!sourceLrcFile.exists()) return
        try {
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(exported.fileName)
            val targetLrcUri = exported.existingLrcUri
                ?: exported.rootDoc.createFile("application/octet-stream", lrcFileName)?.uri
                ?: return
            openTruncatedOutputStream(context, targetLrcUri)?.use { out ->
                sourceLrcFile.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (lrcEx: Exception) {
            if (lrcEx is CancellationException) throw lrcEx
            LogManager.w(
                "CacheExporter: Private audio migrated cleanly, but .lrc export failed for $songId: ${lrcEx.message}"
            )
        }
    }

    private fun cleanupHalfWrittenDoc(docFile: DocumentFile?) {
        try {
            if (docFile != null && docFile.exists()) {
                docFile.delete()
            }
        } catch (cleanupEx: Exception) {
            LogManager.e("CacheExporter: Failed to clean up half-written SAF file", cleanupEx)
        }
    }

    private fun cleanupHalfWrittenFile(file: File?) {
        try {
            if (file != null && file.exists()) {
                file.delete()
            }
        } catch (cleanupEx: Exception) {
            LogManager.e("CacheExporter: Failed to clean up half-written private file", cleanupEx)
        }
    }

    companion object {
        private const val MAX_FILENAME_UTF8_BYTES = 255
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
    }
}
