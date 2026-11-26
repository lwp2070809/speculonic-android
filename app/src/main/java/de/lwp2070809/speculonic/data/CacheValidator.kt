package de.lwp2070809.speculonic.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.domain.model.InconsistentItem.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CacheValidator(private val context: Context) {
    private data class FileDetails(val size: Long?)

    // Scoped to one scan or resolution batch; never reuse across user operations.
    private val directoryIds = ConcurrentHashMap<Uri, Set<String>>()

    fun rememberDirectory(treeUri: Uri, entries: List<SafChildEntry>) {
        directoryIds[treeRoot(treeUri)] = entries.mapTo(HashSet()) { it.documentId }
    }

    companion object {
        private val readSemaphore = Semaphore(2)
    }

    suspend fun inspectFile(uri: Uri, song: SongEntity, deepCheck: Boolean = true, listedSize: Long? = null): Type? =
        withContext(Dispatchers.IO) {
            val details = listedSize?.let(::FileDetails) ?: fileDetails(uri) ?: return@withContext Type.MISSING_FILE
            val size = details.size
            val expectedSize = song.size?.takeIf { it > 0 && !song.isTranscoded }
            val expectedMd5 = song.md5?.takeIf { deepCheck && !song.isTranscoded && it.isNotBlank() }
            if (size != null && invalidSize(size, expectedSize)) return@withContext Type.BINARY_MISMATCH
            if (size != null && expectedMd5 == null) return@withContext null

            try {
                inspectStream(uri, expectedSize, expectedMd5)
            } catch (e: IOException) {
                if (e !is FileNotFoundException && e !is NoSuchFileException) throw e
                if (isMissingAfterReadFailure(uri)) Type.MISSING_FILE else throw e
            }
        }

    private fun invalidSize(length: Long, expectedSize: Long?): Boolean =
        length <= 0 || expectedSize != null && length != expectedSize

    private suspend fun inspectStream(uri: Uri, expectedSize: Long?, expectedMd5: String?): Type? =
        readSemaphore.withPermit {
            val digest = expectedMd5?.let { MessageDigest.getInstance("MD5") }
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read audio file")
            val length = stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest?.update(buffer, 0, count)
                    total += count
                }
                total
            }
            val md5 = digest?.digest()?.joinToString("") { "%02x".format(it) }
            if (invalidSize(length, expectedSize) || !md5.equals(expectedMd5, ignoreCase = true)) Type.BINARY_MISMATCH else null
        }

    @OptIn(UnstableApi::class)
    fun inspectDownloadCache(song: SongEntity, cache: Cache): Type? {
        val spans = cache.getCachedSpans(song.id)
        if (spans.isEmpty()) return Type.MISSING_FILE
        val storedSize = ContentMetadata.getContentLength(cache.getContentMetadata(song.id))
        val expectedSize = song.size?.takeIf { !song.isTranscoded && it > 0 }
        val length = expectedSize ?: storedSize.takeIf { it > 0 } ?: spans.last().let { it.position + it.length }
        return if ((storedSize > 0 && storedSize != length) || !cache.isCached(song.id, 0L, length)) {
            Type.BINARY_MISMATCH
        } else null
    }

    internal fun fileExists(uri: Uri): Boolean = fileDetails(uri) != null

    internal fun isRegularSafFile(uri: Uri): Boolean {
        val column = DocumentsContract.Document.COLUMN_MIME_TYPE
        return queryFile(uri, arrayOf(column))?.use { cursor ->
            checkFileQuery(cursor)
            val index = cursor.getColumnIndex(column)
            cursor.moveToFirst() && index >= 0 && isRegularSafFileType(cursor.getString(index))
        } == true
    }

    private fun fileDetails(uri: Uri): FileDetails? {
        if (uri.scheme == "file") {
            return try {
                FileDetails(Files.size(File(requireNotNull(uri.path)).toPath()))
            } catch (_: NoSuchFileException) {
                null
            }
        }
        return queryFile(uri, arrayOf(OpenableColumns.SIZE))?.use { cursor ->
            checkFileQuery(cursor)
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index).takeIf { it >= 0 }
            FileDetails(size)
        }
    }

    private fun queryFile(uri: Uri, projection: Array<String>): Cursor? = try {
        context.contentResolver.query(uri, projection, null, null, null)
            ?: throw IOException("Document provider did not return file details")
    } catch (e: IllegalArgumentException) {
        val root = directExternalTreeRoot(uri) ?: throw e
        val id = DocumentsContract.getDocumentId(uri)
        val ids = directoryIds.computeIfAbsent(root) { readDirectoryIds(it) }
        if (id in ids && id in refreshDirectoryIds(root)) throw e
        null
    }

    private fun isMissingAfterReadFailure(uri: Uri): Boolean {
        // A stream can fail to open while its document still exists. Check the live provider.
        val root = directExternalTreeRoot(uri) ?: return fileDetails(uri) == null
        return DocumentsContract.getDocumentId(uri) !in refreshDirectoryIds(root)
    }

    private fun directExternalTreeRoot(uri: Uri): Uri? {
        if (uri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(uri)) return null
        val id = DocumentsContract.getDocumentId(uri)
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val childId = id.removePrefix(if (treeId.endsWith(':')) treeId else "$treeId/")
        return if (childId != id && childId.isNotEmpty() && '/' !in childId) treeRoot(uri) else null
    }

    private fun refreshDirectoryIds(root: Uri): Set<String> =
        readDirectoryIds(root).also { directoryIds[root] = it }

    private fun treeRoot(uri: Uri): Uri = DocumentsContract.buildTreeDocumentUri(
        requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri)
    )

    private fun readDirectoryIds(treeUri: Uri): Set<String> =
        SafDirectoryHelper.listChildDocumentIds(context, treeUri)

    private fun checkFileQuery(cursor: Cursor) {
        val extras = cursor.extras
        val error = extras?.getString(DocumentsContract.EXTRA_ERROR)
        if (error != null || extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) {
            throw IOException(error ?: "File details are still loading")
        }
    }
}
