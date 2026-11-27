package de.lwp2070809.speculonic.util

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.CacheExporter
import de.lwp2070809.speculonic.data.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLyricsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    suspend fun getLyricsFromLrcFile(audioUriString: String): String? = withContext(Dispatchers.IO) {
        try {
            if (audioUriString.startsWith("content://")) {
                readSafLyrics(audioUriString)
            } else if (audioUriString.startsWith("file:") || audioUriString.startsWith("/")) {
                readPrivateLyrics(audioUriString)
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("LocalLyricsProvider: Failed to read lyrics file", e)
            null
        }
    }

    private suspend fun readSafLyrics(audioUriString: String): String? {
        val singleDoc = DocumentFile.fromSingleUri(context, audioUriString.toUri())
        val audioName = singleDoc?.name ?: return null
        val targetSafUriString = preferencesManager.cacheLocation.first()
        if (targetSafUriString.isBlank()) return null

        val rootDoc = DocumentFile.fromTreeUri(context, targetSafUriString.toUri())
        val lrcFileName = FormatUtils.replaceExtensionWithLrc(audioName)
        val lrcFile = rootDoc?.findFile(lrcFileName)
        if (lrcFile == null || !lrcFile.exists()) return null

        return context.contentResolver.openInputStream(lrcFile.uri)?.use { input ->
            input.bufferedReader().readText().takeIf { it.isNotBlank() }?.also {
                LogManager.d("LocalLyricsProvider: Found companion .lrc file in SAF")
            }
        }
    }

    private fun readPrivateLyrics(audioUriString: String): String? {
        val physicalPath = FormatUtils.getFullPhysicalPath(audioUriString)
        val lrcPath = FormatUtils.replaceExtensionWithLrc(physicalPath)
        val lrcFile = File(lrcPath)
        if (!lrcFile.exists()) return null

        return lrcFile.readText().takeIf { it.isNotBlank() }?.also {
            LogManager.d("LocalLyricsProvider: Found companion .lrc file in file system")
        }
    }

    suspend fun saveLyricsToLrcFile(audioUriString: String, lrcContent: String): Boolean = withContext(Dispatchers.IO) {
        if (lrcContent.isBlank()) return@withContext false
        try {
            when {
                audioUriString.startsWith("content://") -> saveSafLyrics(audioUriString, lrcContent)
                audioUriString.startsWith("file:") || audioUriString.startsWith("/") ->
                    savePrivateLyricsAtomic(audioUriString, lrcContent)
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogManager.e("LocalLyricsProvider: Failed to save lyrics to .lrc file", e)
            false
        }
    }

    private suspend fun saveSafLyrics(audioUriString: String, lrcContent: String): Boolean {
        val targetSafUriString = preferencesManager.cacheLocation.first()
        if (targetSafUriString.isBlank()) return false

        val rootDoc = DocumentFile.fromTreeUri(context, targetSafUriString.toUri())
        if (rootDoc == null || !rootDoc.canWrite()) return false

        val singleDoc = DocumentFile.fromSingleUri(context, audioUriString.toUri())
        val audioName = singleDoc?.name ?: return false
        val lrcFileName = FormatUtils.replaceExtensionWithLrc(audioName)
        val existingLrc = rootDoc.findFile(lrcFileName)
        val lrcFile = existingLrc ?: rootDoc.createFile("application/octet-stream", lrcFileName) ?: return false

        CacheExporter.openTruncatedOutputStream(context, lrcFile.uri, newFile = existingLrc == null)?.use { out ->
            out.write(lrcContent.toByteArray())
            LogManager.i("LocalLyricsProvider: Successfully created and saved companion .lrc file")
            return true
        }
        return false
    }

    private fun savePrivateLyricsAtomic(audioUriString: String, lrcContent: String): Boolean {
        val physicalPath = FormatUtils.getFullPhysicalPath(audioUriString)
        val lrcPath = FormatUtils.replaceExtensionWithLrc(physicalPath)
        val lrcFile = File(lrcPath)
        val tempFile = File("$lrcPath.tmp")
        tempFile.parentFile?.mkdirs()
        // 先写入 .lrc.tmp 再执行原子重命名，防止写入中途进程崩溃留下半写文件
        tempFile.writeText(lrcContent)
        return if (tempFile.renameTo(lrcFile)) {
            LogManager.i("LocalLyricsProvider: Saved companion .lrc file to local storage (atomic)")
            true
        } else {
            tempFile.delete()
            LogManager.e("LocalLyricsProvider: Failed to atomic rename .lrc.tmp to .lrc")
            false
        }
    }
}
