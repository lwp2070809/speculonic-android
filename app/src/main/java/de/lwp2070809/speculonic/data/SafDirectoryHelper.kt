package de.lwp2070809.speculonic.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.LogManager
import java.io.IOException

data class SafChildEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String?
)

internal fun isRegularSafFileType(mimeType: String?): Boolean =
    !mimeType.isNullOrBlank() && mimeType != DocumentsContract.Document.MIME_TYPE_DIR

internal fun Cursor.requireCompleteSafResult() {
    val error = extras?.getString(DocumentsContract.EXTRA_ERROR)
    if (error != null || extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true) {
        throw IOException(error ?: "Document details are still loading")
    }
}

/**
 * 通过单次 DocumentsContract 树形目录游标批量读取 SAF 子项，替代 DocumentFile.listFiles/findFile 的 O(N) 逐条 Binder IPC。
 */
object SafDirectoryHelper {

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    fun listChildEntries(context: Context, treeUri: Uri): List<SafChildEntry> =
        withChildCursor(context, treeUri, CHILD_PROJECTION) { readCursorEntries(it, treeUri) }

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
        read: (Cursor) -> T
    ): T {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val cursor = context.contentResolver.query(children, projection, null, null, null)
            ?: throw IOException("Document provider did not return a directory listing")
        return cursor.use {
            it.requireCompleteSafResult()
            read(it)
        }
    }

    fun findWriteTargets(context: Context, treeUri: Uri, names: Set<String>): Map<String, SafChildEntry> =
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

    private fun readCursorEntries(cursor: Cursor, treeUri: Uri): List<SafChildEntry> {
        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        return buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                if (id.isNullOrBlank() || name.isNullOrBlank()) {
                    LogManager.w("SafDirectoryHelper: Skipping document without ID or name")
                    continue
                }
                val mime = cursor.getString(mimeIndex)
                if (mime.isNullOrBlank()) throw IOException("Missing document MIME type: $name")
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                add(SafChildEntry(id, name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size, mime))
            }
        }
    }
}
