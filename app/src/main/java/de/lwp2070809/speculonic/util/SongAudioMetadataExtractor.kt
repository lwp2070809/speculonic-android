package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import de.lwp2070809.speculonic.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object SongAudioMetadataExtractor {

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
            var file = File(physicalPath)

            if ((!file.exists() || !file.canRead()) && uriString.startsWith("content://")) {
                val ext = if (isTranscoded) {
                    val decodedUri = Uri.decode(uriString)
                    val segmentExt = decodedUri.substringAfterLast('.', "")
                        .substringBefore('?')
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                    segmentExt ?: PreferencesManager.getInstance(context).targetTranscodeFormat.first().lowercase()
                } else {
                    suffix?.lowercase()
                        ?: Uri.decode(uriString).substringAfterLast('.', "")
                            .substringBefore('?')
                            .lowercase()
                            .takeIf { it.isNotEmpty() }
                        ?: "mp3"
                }
                val createdTemp = File.createTempFile("temp_tag_parsing", ".$ext", context.cacheDir)
                tempFile = createdTemp
                context.contentResolver.openInputStream(uriString.toUri())?.use { input ->
                    createdTemp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                file = createdTemp
            }

            if (!file.exists() || !file.canRead()) return@withContext emptyMap()

            val metadataMap = mutableMapOf<String, String>()
            val audioFile = AudioFileIO.read(file)

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
                metadataMap["__FORMAT_INFO__"] = formatStr
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
                    metadataMap["Lyrics (Parsed)"] = lyrics
                }

                val fields = tag.fields
                while (fields.hasNext()) {
                    val field = fields.next()
                    val key = field.id
                    val value = if (field.isBinary) "[Binary Data]" else field.toString()

                    if (!key.equals("APIC", ignoreCase = true) && !key.equals("PIC", ignoreCase = true)) {
                        val cleanValue = value.replace(Regex("^Text=\"?|\"?$"), "").trim()
                        metadataMap["Raw: $key"] = cleanValue
                    }
                }

                val hasCover = tag.firstArtwork != null
                metadataMap["Embedded Cover"] = if (hasCover) "Yes" else "No"
            }

            metadataMap.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyMap()
        } finally {
            tempFile?.delete()
        }
    }
}
