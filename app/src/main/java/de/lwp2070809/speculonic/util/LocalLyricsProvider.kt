package de.lwp2070809.speculonic.util

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object LocalLyricsProvider {

    
    suspend fun getLyricsFromLrcFile(context: Context, audioUriString: String): String? = withContext(Dispatchers.IO) {
        try {
            if (audioUriString.startsWith("content://")) {
                val singleDoc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, audioUriString.toUri())
                val audioName = singleDoc?.name
                if (audioName != null) {
                    val preferencesManager = de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context)
                    val targetSafUriString = preferencesManager.cacheLocation.first()
                    if (targetSafUriString.isNotBlank()) {
                        val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetSafUriString.toUri())
                        val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(audioName)
                        val lrcFile = rootDoc?.findFile(lrcFileName)
                        if (lrcFile != null && lrcFile.exists()) {
                            context.contentResolver.openInputStream(lrcFile.uri)?.use { input ->
                                val content = input.bufferedReader().readText()
                                if (content.isNotBlank()) {
                                    LogManager.d("LocalLyricsProvider: Found companion .lrc file in SAF")
                                    return@withContext content
                                }
                            }
                        }
                    }
                }
            } else if (audioUriString.startsWith("file:") || audioUriString.startsWith("/")) {
                val physicalPath = de.lwp2070809.speculonic.util.FormatUtils.getFullPhysicalPath(audioUriString)
                val lrcPath = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(physicalPath)
                val lrcFile = java.io.File(lrcPath)
                if (lrcFile.exists()) {
                    val content = lrcFile.readText()
                    if (content.isNotBlank()) {
                        LogManager.d("LocalLyricsProvider: Found companion .lrc file in file system")
                        return@withContext content
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("LocalLyricsProvider: Failed to read lyrics file", e)
        }
        null
    }

    
    suspend fun saveLyricsToLrcFile(context: Context, audioUriString: String, lrcContent: String): Boolean = withContext(Dispatchers.IO) {
        if (lrcContent.isBlank()) return@withContext false
        try {
            if (audioUriString.startsWith("content://")) {
                val preferencesManager = de.lwp2070809.speculonic.data.PreferencesManager.getInstance(context)
                val targetSafUriString = preferencesManager.cacheLocation.first()
                if (targetSafUriString.isNotBlank()) {
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetSafUriString.toUri())
                    if (rootDoc != null && rootDoc.canWrite()) {
                        
                        val singleDoc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, audioUriString.toUri())
                        val audioName = singleDoc?.name
                        if (audioName != null) {
                            val lrcFileName = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(audioName)
                            val existingLrc = rootDoc.findFile(lrcFileName)
                            val lrcFile = existingLrc ?: rootDoc.createFile("application/octet-stream", lrcFileName)
                            
                            lrcFile?.let { file ->
                                context.contentResolver.openOutputStream(file.uri)?.use { out ->
                                    out.write(lrcContent.toByteArray())
                                    LogManager.i("LocalLyricsProvider: Successfully created and saved companion .lrc file")
                                    return@withContext true
                                }
                            }
                        }
                    }
                }
            } else if (audioUriString.startsWith("file:") || audioUriString.startsWith("/")) {
                val physicalPath = de.lwp2070809.speculonic.util.FormatUtils.getFullPhysicalPath(audioUriString)
                val lrcPath = de.lwp2070809.speculonic.util.FormatUtils.replaceExtensionWithLrc(physicalPath)
                val lrcFile = java.io.File(lrcPath)
                val tempFile = java.io.File(lrcPath + ".tmp")
                tempFile.parentFile?.mkdirs()
                tempFile.writeText(lrcContent)
                if (tempFile.renameTo(lrcFile)) {
                    LogManager.i("LocalLyricsProvider: Saved companion .lrc file to local storage (atomic)")
                    return@withContext true
                } else {
                    tempFile.delete()
                    LogManager.e("LocalLyricsProvider: Failed to atomic rename .lrc.tmp to .lrc")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            LogManager.e("LocalLyricsProvider: Failed to save lyrics to .lrc file", e)
        }
        return@withContext false
    }
}

