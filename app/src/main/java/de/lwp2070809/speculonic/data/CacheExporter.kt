package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.data.db.dao.MusicDao
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.util.FormatUtils
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal class MissingPrivateAudioException : IOException("私有源文件不存在")

@OptIn(UnstableApi::class)
@Singleton
class CacheExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    private val privateFilesMutex = Mutex()
    private val activeStagingFiles = mutableSetOf<File>()

    // Bounded locks keep concurrent publication, deletion and source cleanup ordered for each song.
    private val exportLocks = Array(32) { Mutex() }
    internal suspend fun <T> withSongExport(songId: String, block: suspend () -> T): T =
        exportLocks[Math.floorMod(songId.hashCode(), exportLocks.size)].withLock { block() }

    private fun isAbandonedStagingFile(file: File): Boolean =
        file.name.startsWith("export-") && file.extension == "part" && file !in activeStagingFiles

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
            if (input.copyTo(outputStream) != contentLength) throw IOException("Incomplete cached audio")
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
            openTruncatedOutputStream(context, uri, newFile = existingLrcUri == null)?.use { out ->
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
            openTruncatedOutputStream(context, targetDocUri, newFile = cleanupDocFile != null)?.use { outputStream ->
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
        lyrics: String?,
        cacheDataSourceFactory: CacheDataSource.Factory,
        targetTranscodeFormat: String?,
        register: suspend (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val cache = cacheDataSourceFactory.cache
            ?: return@withContext Result.failure(IllegalStateException("缓存实例缺失"))
        val length = getCacheContentLength(cache, song.id)
        if (length <= 0) return@withContext Result.failure(IOException("缓存文件不完整"))
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "media_exported_private")
        val suffix = resolveExtension(song, targetTranscodeFormat, preferencesManager.targetTranscodeFormat.first())
        val target = File(directory, buildExportFileName(song, suffix))
        var staging: File? = null
        try {
            directory.mkdirs()
            val temporary = privateFilesMutex.withLock {
                File.createTempFile("export-", ".part", directory).also(activeStagingFiles::add)
            }
            staging = temporary
            temporary.outputStream().use { streamCacheToOutput(cache, song.id, length, it) }
            val uri = publishPrivateExport(song.id, directory, temporary, target, lyrics, register)
            Result.success(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                privateFilesMutex.withLock {
                    staging?.let { activeStagingFiles.remove(it) }
                    cleanupHalfWrittenFile(staging)
                }
            }
        }
    }

    private suspend fun publishPrivateExport(
        songId: String,
        directory: File,
        temporary: File,
        target: File,
        lyrics: String?,
        register: suspend (String) -> Unit
    ): String = privateFilesMutex.withLock {
        val existedBefore = target.exists()
        val uri = target.toURI().toString()
        moveStagingToTarget(temporary, target)
        val writtenLrc = writeOptionalPrivateLyrics(songId, directory, target.name, lyrics)
        try {
            register(uri)
        } catch (e: Exception) {
            if (!existedBefore || e is IllegalStateException) {
                cleanupHalfWrittenFile(target)
                cleanupHalfWrittenFile(writtenLrc)
            }
            throw e
        }
        uri
    }

    private fun moveStagingToTarget(staging: File, target: File) {
        try {
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            LogManager.w("CacheExporter: Atomic move not supported for ${target.name}, using standard move: ${e.message}")
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeOptionalPrivateLyrics(
        songId: String,
        privateDir: File,
        finalFileName: String,
        lyrics: String?
    ): File? {
        if (lyrics.isNullOrBlank()) return null
        val lrcFile = File(privateDir, FormatUtils.replaceExtensionWithLrc(finalFileName))
        return try {
            lrcFile.writeText(lyrics)
            lrcFile
        } catch (lrcEx: Exception) {
            if (lrcEx is CancellationException) throw lrcEx
            LogManager.w(
                "CacheExporter: Private audio exported cleanly, but .lrc write failed for $songId: ${lrcEx.message}"
            )
            cleanupHalfWrittenFile(lrcFile)
            null
        }
    }

    internal suspend fun cleanupOrphanedPrivateFiles(musicDao: MusicDao): Unit = withContext(Dispatchers.IO) {
        privateFilesMutex.withLock {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val privateExportedDir = File(baseDir, "media_exported_private")
            if (!privateExportedDir.exists()) return@withLock

            val validPrivatePaths = musicDao.getAllCachedSongs()
                .mapNotNull { it.localUri }
                .filter { it.startsWith("file:") }
                .mapNotNull { it.toUri().path }
                .toSet()
            val validPrivateLrcPaths = validPrivatePaths
                .map { FormatUtils.replaceExtensionWithLrc(it) }
                .toSet()

            privateExportedDir.listFiles()
                ?.filter { it.isFile && it.name != ".nomedia" }
                ?.forEach { file ->
                    val isLrc = file.name.endsWith(".lrc")
                    val isAudio = FormatUtils.isSupportedAudioFile(file.name)
                    val isOrphan = (isAudio && !validPrivatePaths.contains(file.absolutePath)) ||
                        (isLrc && !validPrivateLrcPaths.contains(file.absolutePath))
                    if (isOrphan || isAbandonedStagingFile(file)) {
                        file.delete()
                        LogManager.i("CacheSync: Deleted orphaned private file: ${file.name}")
                    }
                }
        }
    }

    internal suspend fun migratePrivateFileToSaf(
        song: Song,
        privateFileUriString: String,
        database: AppDatabase
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val targetSafUriString = preferencesManager.cacheLocation.first().takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("SAF未配置"))

        val sourcePath = privateFileUriString.toUri().path
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid private file path"))
        val sourceFile = File(sourcePath)
        if (Files.notExists(sourceFile.toPath())) {
            return@withContext Result.failure(MissingPrivateAudioException())
        }

        var staging: DocumentFile? = null
        try {
            val resolvedRootDoc = getCachedOrCreateRootDoc(targetSafUriString)
                ?: throw IOException("无法访问目标文件夹")
            val suffix = sourceFile.extension.takeIf { it.isNotBlank() }?.lowercase()
                ?: resolveExtension(song, null, preferencesManager.targetTranscodeFormat.first())
            val resolvedFileName = buildExportFileName(song, suffix)
            val lrcFileName = FormatUtils.replaceExtensionWithLrc(resolvedFileName)
            val treeUri = targetSafUriString.toUri()
            val targetNames = setOf(resolvedFileName, lrcFileName)
            val preScanned = SafDirectoryHelper.findMigrationTargets(context, treeUri, targetNames)
            val existing = preScanned[resolvedFileName]
            val matches = existing != null && safFileMatchesSource(existing, sourceFile)
            val mime = FormatUtils.getMimeTypeFromExtension(suffix)
            if (!matches) {
                // No [songId] in this name: even an interrupted/conflicting copy cannot be auto-linked.
                staging = resolvedRootDoc.createFile(mime, "migration-${UUID.randomUUID()}.part")
                    ?: throw IOException("无法创建临时文件")
                copyPrivateAudio(sourceFile, staging.uri)
            }
            val dao = database.musicDao()
            val committed = database.withTransaction {
                if (!canCommitMigration(dao, song.id, privateFileUriString, treeUri, targetNames, preScanned, existing)) {
                    return@withTransaction false
                }
                copyOptionalPrivateLrcToSaf(sourceFile, resolvedRootDoc, lrcFileName, preScanned[lrcFileName]?.uri)
                val temporary = staging
                val targetUri = if (temporary == null) checkNotNull(existing).uri else {
                    val published = publishMigrationAudio(temporary, sourceFile, resolvedRootDoc, resolvedFileName, mime, existing)
                    if (published === temporary) staging = null
                    published.uri
                }
                check(dao.moveCachedFile(song.id, privateFileUriString, targetUri.toString()) == 1)
                true
            }
            Result.success(committed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("CacheExporter: Failed to export private file to SAF for ${song.id}", e)
            Result.failure(e)
        } finally {
            cleanupHalfWrittenDoc(staging)
        }
    }

    private suspend fun canCommitMigration(
        dao: MusicDao,
        songId: String,
        source: String,
        treeUri: Uri,
        names: Set<String>,
        scanned: Map<String, SafChildEntry>,
        audio: SafChildEntry?
    ): Boolean {
        val current = dao.getSongById(songId)
        if (current?.isFullyCached != true || current.localUri != source) return false
        val targets = SafDirectoryHelper.findMigrationTargets(context, treeUri, names)
        names.firstOrNull { scanned[it] != targets[it] }?.let { name ->
            throw IOException(context.getString(R.string.saf_name_conflict, name))
        }
        if (audio != null && dao.hasSongAtUri(audio.uri.toString())) {
            throw IOException("Migration target already belongs to a cached song")
        }
        return true
    }

    private fun publishMigrationAudio(
        staging: DocumentFile,
        source: File,
        root: DocumentFile,
        name: String,
        mime: String,
        existing: SafChildEntry?
    ): DocumentFile {
        if (existing != null && DocumentFile.fromSingleUri(context, existing.uri)?.delete() != true) {
            throw IOException("Unable to replace SAF migration target")
        }
        if (staging.renameTo(name)) return staging
        // Providers without rename support can still publish a regular document.
        val target = root.createFile(mime, name) ?: throw IOException("无法创建目标文件")
        try {
            copyPrivateAudio(source, target.uri)
            return target
        } catch (e: Exception) {
            cleanupHalfWrittenDoc(target)
            throw e
        }
    }

    private fun copyPrivateAudio(sourceFile: File, target: Uri) {
        val copiedBytes = openTruncatedOutputStream(context, target, newFile = true)?.use { outputStream ->
            sourceFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
        } ?: throw IOException("Failed to open SAF output stream")
        if (copiedBytes != sourceFile.length()) {
            throw IOException("Incomplete SAF audio export: wrote $copiedBytes of ${sourceFile.length()}")
        }
    }

    private fun safFileMatchesSource(entry: SafChildEntry, sourceFile: File): Boolean {
        if (entry.size != sourceFile.length()) return false
        return try {
            val safStream = context.contentResolver.openInputStream(entry.uri) ?: return false
            safStream.use { existingStream ->
                sourceFile.inputStream().use { sourceStream ->
                    streamsHaveIdenticalContent(existingStream, sourceStream)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogManager.w("CacheExporter: Failed comparing SAF file with private source (${e.message})")
            false
        }
    }

    private fun copyOptionalPrivateLrcToSaf(
        sourceFile: File,
        root: DocumentFile,
        lrcFileName: String,
        existingLrcUri: Uri?
    ) {
        val sourceLrcFile = File(FormatUtils.replaceExtensionWithLrc(sourceFile.absolutePath))
        if (!sourceLrcFile.exists()) return
        val createdDoc = if (existingLrcUri == null) {
            root.createFile("application/octet-stream", lrcFileName)
                ?: throw IOException("Unable to create lyrics file")
        } else {
            null
        }
        val targetLrcUri = existingLrcUri ?: checkNotNull(createdDoc).uri
        runCatching {
            openTruncatedOutputStream(context, targetLrcUri, newFile = createdDoc != null)?.use { out ->
                sourceLrcFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Unable to open lyrics file")
        }.onFailure { cleanupHalfWrittenDoc(createdDoc) }.getOrThrow()
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
        private const val STREAM_COMPARE_BUFFER_BYTES = 65_536

        internal fun streamsHaveIdenticalContent(first: InputStream, second: InputStream): Boolean {
            val firstBuf = ByteArray(STREAM_COMPARE_BUFFER_BYTES)
            val secondBuf = ByteArray(STREAM_COMPARE_BUFFER_BYTES)
            while (true) {
                val firstRead = readChunkOrEof(first, firstBuf)
                val secondRead = readChunkOrEof(second, secondBuf)
                if (firstRead != secondRead) return false
                if (firstRead == 0) return true
                if (!Arrays.equals(firstBuf, 0, firstRead, secondBuf, 0, secondRead)) return false
            }
        }

        private fun readChunkOrEof(stream: InputStream, buffer: ByteArray): Int {
            var totalRead = 0
            while (totalRead < buffer.size) {
                val count = stream.read(buffer, totalRead, buffer.size - totalRead)
                if (count == -1) break
                totalRead += count
            }
            return totalRead
        }

        internal fun openTruncatedOutputStream(context: Context, uri: Uri, newFile: Boolean): OutputStream? {
            val resolver = context.contentResolver
            if (newFile) return resolver.openOutputStream(uri, "w")
            try {
                return resolver.openOutputStream(uri, "wt")
            } catch (e: IllegalArgumentException) {
                LogManager.w("CacheExporter: Provider rejected truncate mode: ${e.message}")
            } catch (e: UnsupportedOperationException) {
                LogManager.w("CacheExporter: Provider does not support truncate mode: ${e.message}")
            }
            // Providers can open an existing document in "w" without truncating it.
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: return null
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                stream.channel.truncate(0L)
                return stream
            } catch (e: IOException) {
                stream.close()
                throw e
            }
        }
    }
}
