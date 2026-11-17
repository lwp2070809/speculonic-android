package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import de.lwp2070809.speculonic.data.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object SongAudioMetadataExtractor {
    const val KEY_FORMAT_INFO = "__FORMAT_INFO__"
    const val KEY_LYRICS_PARSED = "Lyrics (Parsed)"

    private val TEXT_CLEAN_REGEX = Regex("^Text=\"?|\"?$")

    suspend fun calculateSha1(uriString: String, context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-1")
            val uri = uriString.toUri()
            val inputStream = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else if (uri.scheme == "file") {
                FileInputStream(uri.path)
            } else {
                FileInputStream(uriString)
            }
            inputStream?.use { fis ->
                val buffer = ByteArray(8192)
                var n = fis.read(buffer)
                while (n != -1) {
                    digest.update(buffer, 0, n)
                    n = fis.read(buffer)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun extractId3(
        uriString: String,
        suffix: String?,
        isTranscoded: Boolean,
        context: Context
    ): Map<String, String> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            TagOptionSingleton.getInstance().isAndroid = true

            val physicalPath = FormatUtils.getFullPhysicalPath(uriString)
            val directFile = File(physicalPath)

            val audioFile: AudioFile? = when {
                directFile.exists() && directFile.canRead() -> AudioFileIO.read(directFile)
                uriString.startsWith("content://") -> {
                    val ext = resolveExtension(uriString, suffix, isTranscoded, context)
                    // 优先尝试基于文件描述符直读避免大文件整包拷入 cacheDir；若底层拒绝再回退临时文件
                    readViaFileDescriptor(uriString, ext, context) ?: run {
                        val createdTemp = File.createTempFile("temp_tag_parsing", ".$ext", context.cacheDir)
                        tempFile = createdTemp
                        context.contentResolver.openInputStream(uriString.toUri())?.use { input ->
                            createdTemp.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (createdTemp.exists() && createdTemp.canRead()) {
                            AudioFileIO.read(createdTemp)
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

            if (audioFile == null) return@withContext emptyMap()

            val metadataMap = mutableMapOf<String, String>()

            val header = audioFile.audioHeader
            if (header != null) {
                metadataMap["Encoding Format"] = header.format ?: ""
                metadataMap["Sample Rate"] = header.sampleRate?.let { "$it Hz" } ?: ""
                metadataMap["Channels"] = header.channels ?: ""
                metadataMap["BitRate (Internal)"] = header.bitRate?.let { "$it kbps" } ?: ""
            }

            val formatStr = buildString {
                if (header != null) {
                    append(header.format ?: "")
                }
                val tag = audioFile.tag
                if (tag != null) {
                    val tagType = tag.javaClass.simpleName.replace("Tag", "")
                    if (isNotEmpty() && tagType.isNotEmpty()) append(" / ")
                    append(tagType)
                }
            }
            if (formatStr.isNotEmpty()) {
                metadataMap[KEY_FORMAT_INFO] = formatStr
            }

            val tag = audioFile.tag
            if (tag != null) {
                metadataMap["Title"] = tag.getFirst(FieldKey.TITLE) ?: ""
                metadataMap["Artist"] = tag.getFirst(FieldKey.ARTIST) ?: ""
                metadataMap["Album"] = tag.getFirst(FieldKey.ALBUM) ?: ""
                metadataMap["Year"] = tag.getFirst(FieldKey.YEAR) ?: ""
                metadataMap["Track"] = tag.getFirst(FieldKey.TRACK) ?: ""
                metadataMap["Genre"] = tag.getFirst(FieldKey.GENRE) ?: ""

                val lyrics = tag.getFirst(FieldKey.LYRICS)
                if (!lyrics.isNullOrEmpty()) {
                    metadataMap[KEY_LYRICS_PARSED] = lyrics
                }

                val fields = tag.fields
                while (fields.hasNext()) {
                    val field = fields.next()
                    val key = field.id
                    val value = if (field.isBinary) "[Binary Data]" else field.toString()

                    if (!key.equals("APIC", ignoreCase = true) && !key.equals("PIC", ignoreCase = true)) {
                        val cleanValue = value.replace(TEXT_CLEAN_REGEX, "").trim()
                        metadataMap["Raw: $key"] = cleanValue
                    }
                }

                val hasCover = tag.firstArtwork != null
                metadataMap["Embedded Cover"] = if (hasCover) "Yes" else "No"
            }

            metadataMap.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyMap()
        } finally {
            tempFile?.delete()
        }
    }

    private suspend fun resolveExtension(
        uriString: String,
        suffix: String?,
        isTranscoded: Boolean,
        context: Context
    ): String {
        val decodedUri = Uri.decode(uriString)
        val uriExt = decodedUri.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it.isNotBlank() }
        return if (isTranscoded) {
            uriExt ?: PreferencesManager.getInstance(context).targetTranscodeFormat.first().lowercase()
        } else {
            suffix?.lowercase() ?: uriExt ?: "mp3"
        }
    }

    private fun readViaFileDescriptor(
        uriString: String,
        ext: String,
        context: Context
    ): AudioFile? {
        return try {
            context.contentResolver.openFileDescriptor(uriString.toUri(), "r")?.use { pfd ->
                val fdFile = File("/proc/self/fd/${pfd.fd}")
                if (fdFile.exists() && fdFile.canRead()) {
                    AudioFileIO.readAs(fdFile, ext)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
