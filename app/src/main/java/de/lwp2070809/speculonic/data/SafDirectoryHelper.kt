package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException

data class SafChildEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String?
)

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
        scanChildren(context, treeUri, targetNames = null)

    fun findChildrenByName(context: Context, treeUri: Uri, targetNames: Set<String>): Map<String, SafChildEntry> {
        if (targetNames.isEmpty()) return emptyMap()
        return scanChildren(context, treeUri, targetNames = targetNames).associateBy { it.name }
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
        if (idIdx < 0 || nameIdx < 0) return emptyList()
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
        if (docId.isNullOrBlank() || name.isNullOrBlank()) return null
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
