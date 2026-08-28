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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class CacheValidator(private val context: Context) {

    companion object {
        private val md5Semaphore = Semaphore(2)
    }

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

            if (length <= 0) {
                LogManager.i("CacheValidator: File is empty for ${dbSong.title}")
                return@withContext false
            }

            // 如果该文件是服务端转码后导出的，其大小和哈希必然不同于服务端的 Raw 原始元数据，豁免严格比对
            if (dbSong.isTranscoded) {
                LogManager.d("CacheValidator: ${dbSong.title} is marked as transcoded. Size/MD5 check skipped.")
                return@withContext true
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
            md5Semaphore.withPermit {
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
            }
        } catch (e: Exception) {
            LogManager.e("CacheValidator: Failed to calculate MD5", e)
            null
        }
    }
}
