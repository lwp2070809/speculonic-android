package de.lwp2070809.speculonic.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.lwp2070809.speculonic.data.db.entities.SongEntity
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class CacheValidator(private val context: Context) {

    suspend fun checkBinaryConsistency(uri: Uri, dbSong: SongEntity, deepCheck: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val length = if (uri.scheme == "file") {
                val file = File(uri.path ?: return@withContext false)
                if (!file.exists()) return@withContext false
                file.length()
            } else {
                val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
                if (!docFile.exists()) return@withContext false
                docFile.length()
            }

            if (dbSong.size != null && dbSong.size > 0) {
                if (length != dbSong.size) {
                    LogManager.i("CacheValidator: Size mismatch for ${dbSong.title}. Local: $length, Expected: ${dbSong.size}")
                    return@withContext false
                }
            }

            if (deepCheck && !dbSong.md5.isNullOrBlank()) {
                val localMd5 = calculateMd5(uri)
                if (localMd5 != dbSong.md5) {
                    LogManager.i("CacheValidator: MD5 mismatch for ${dbSong.title}. Local: $localMd5, Expected: ${dbSong.md5}")
                    return@withContext false
                }
            }
            return@withContext true
        } catch (e: Exception) {
            LogManager.e("CacheValidator: Failed binary check for ${dbSong.title}", e)
            false
        }
    }

    suspend fun calculateMd5(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("MD5")
            val stream = if (uri.scheme == "file") {
                val file = File(uri.path ?: return@withContext null)
                file.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
            stream?.use { input ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LogManager.e("CacheValidator: Failed to calculate MD5", e)
            null
        }
    }
}
