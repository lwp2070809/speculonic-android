package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.LogManager
import java.io.IOException
import kotlinx.coroutines.CancellationException

data class SafChildEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String?
)

internal fun isRegularSafFileType(mimeType: String?): Boolean =
    !mimeType.isNullOrBlank() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR

/**
 * 通过单次 DocumentsContract 树形目录游标批量读取 SAF 子项，替代 DocumentFile.listFiles/findFile 的 O(N) 逐条 Binder IPC。
 */
object SafDirectoryHelper {

    private const val DEFAULT_ENTRY_CAPACITY = 16

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    fun listChildEntries(context: Context, treeUri: Uri): List<SafChildEntry> =
        withChildCursor(context, treeUri, CHILD_PROJECTION) { readCursorEntries(it, treeUri, null) }

    fun listChildDocumentIds(context: Context, treeUri: Uri): Set<String> =
        withChildCursor(context, treeUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) { cursor ->
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            buildSet {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(index)
                    if (id.isNullOrBlank()) throw IOException("Incomplete directory listing")
                    add(id)
                }
            }
        }

    private inline fun <T> withChildCursor(
        context: Context,
        treeUri: Uri,
        projection: Array<String>,
        read: (android.database.Cursor) -> T
    ): T {
        val children = resolveChildrenUri(treeUri) ?: throw IOException("Invalid document tree")
        val cursor = context.contentResolver.query(children, projection, null, null, null)
            ?: throw IOException("Document provider did not return a directory listing")
        return cursor.use {
            val extras = it.extras
            extras?.getString(DocumentsContract.EXTRA_ERROR)?.let { message -> throw IOException(message) }
            if (extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) throw IOException("Directory is still loading")
            read(it)
        }
    }

    fun findChildrenByName(context: Context, treeUri: Uri, targetNames: Set<String>): Map<String, SafChildEntry> {
        if (targetNames.isEmpty()) return emptyMap()
        return scanChildren(context, treeUri, targetNames = targetNames).associateBy { it.name }
    }

    fun findMigrationTargets(context: Context, treeUri: Uri, names: Set<String>): Map<String, SafChildEntry> =
        withChildCursor(context, treeUri, CHILD_PROJECTION) { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            buildMap {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: throw IOException("Incomplete directory listing")
                    if (name !in names) continue
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    if (id.isNullOrBlank() || !isRegularSafFileType(mime) || containsKey(name)) {
                        throw IOException(context.getString(R.string.saf_name_conflict, name))
                    }
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    put(name, SafChildEntry(id, name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size, mime))
                }
            }
        }

    private fun scanChildren(context: Context, treeUri: Uri, targetNames: Set<String>?): List<SafChildEntry> {
        val childrenUri = resolveChildrenUri(treeUri)
            ?: return listViaDocumentFileFallback(context, treeUri, targetNames)
        return queryChildrenEntries(context, treeUri, childrenUri, targetNames)
    }

    private fun resolveChildrenUri(treeUri: Uri): Uri? = try {
        DocumentsContract.getTreeDocumentId(treeUri)?.let { rootDocId ->
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        }
    } catch (e: IllegalArgumentException) {
        LogManager.w("SafDirectoryHelper: Invalid treeUri, falling back to DocumentFile: ${e.message}")
        null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun queryChildrenEntries(
        context: Context,
        treeUri: Uri,
        childrenUri: Uri,
        targetNames: Set<String>?
    ): List<SafChildEntry> = try {
        val cursor = context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
            ?: return listViaDocumentFileFallback(context, treeUri, targetNames)
        cursor.use { readCursorEntries(it, treeUri, targetNames) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        // 第三方或 OEM DocumentsProvider 可能抛出非标准运行时异常，除权限与取消外统一降级至 DocumentFile
        LogManager.w("SafDirectoryHelper: DocumentsContract query failed, falling back to DocumentFile: ${e.message}")
        listViaDocumentFileFallback(context, treeUri, targetNames)
    }

    private fun readCursorEntries(
        cursor: android.database.Cursor,
        treeUri: Uri,
        targetNames: Set<String>?
    ): List<SafChildEntry> {
        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (idIdx < 0 || nameIdx < 0) throw IOException("Incomplete directory listing")
        val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

        val entries = ArrayList<SafChildEntry>(targetNames?.size ?: DEFAULT_ENTRY_CAPACITY)
        val matchedNames = targetNames?.let { HashSet<String>(it.size) }
        val targetCount = targetNames?.size ?: 0
        var allTargetsFound = false
        while (!allTargetsFound && cursor.moveToNext()) {
            val entry = extractCursorEntry(cursor, treeUri, idIdx, nameIdx, sizeIdx, mimeIdx, targetNames)
            if (entry != null) {
                entries.add(entry)
                if (matchedNames != null) {
                    matchedNames.add(entry.name)
                    allTargetsFound = matchedNames.size >= targetCount
                }
            }
        }
        return entries
    }

    private fun extractCursorEntry(
        cursor: android.database.Cursor,
        treeUri: Uri,
        idIdx: Int,
        nameIdx: Int,
        sizeIdx: Int,
        mimeIdx: Int,
        targetNames: Set<String>?
    ): SafChildEntry? {
        val docId = cursor.getString(idIdx)
        val name = cursor.getString(nameIdx)
        if (docId.isNullOrBlank() || name.isNullOrBlank()) {
            LogManager.w("SafDirectoryHelper: Skipping incomplete document entry (docId=$docId, name=$name)")
            return null
        }
        if (targetNames != null && name !in targetNames) return null
        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
        val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) cursor.getString(mimeIdx) else null
        return SafChildEntry(docId, name, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), size, mime)
    }

    private fun listViaDocumentFileFallback(
        context: Context,
        treeUri: Uri,
        targetNames: Set<String>? = null
    ): List<SafChildEntry> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (targetNames != null && targetNames.size == 1) {
            val matched = rootDoc.findFile(targetNames.single()) ?: return emptyList()
            return listOfNotNull(matched.toSafChildEntry())
        }
        return rootDoc.listFiles().mapNotNull { file ->
            val entry = file.toSafChildEntry() ?: return@mapNotNull null
            if (targetNames != null && entry.name !in targetNames) return@mapNotNull null
            entry
        }
    }

    private fun DocumentFile.toSafChildEntry(): SafChildEntry? {
        val fileName = name ?: return null
        return SafChildEntry(
            documentId = uri.lastPathSegment ?: fileName,
            name = fileName,
            uri = uri,
            size = length(),
            mimeType = type
        )
    }
}
