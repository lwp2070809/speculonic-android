package de.lwp2070809.speculonic.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.data.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class CopyLimit { FILE_SIZE, FREE_SPACE }

internal class CopyLimitException(val limit: CopyLimit) : IOException(limit.name)

@Singleton
class SongAudioMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        const val KEY_FORMAT_INFO = "__FORMAT_INFO__"
        const val KEY_LYRICS_PARSED = "Lyrics (Parsed)"

        private const val MAX_TEMP_BYTES = 256L * 1024 * 1024
        private const val RESERVED_BYTES = 128L * 1024 * 1024

        private val TEXT_CLEAN_REGEX = Regex("^Text=\"?|\"?$")
    }

    suspend fun calculateSha1(uriString: String): String? = withContext(Dispatchers.IO) {
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
                    currentCoroutineContext().ensureActive()
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
        isTranscoded: Boolean
    ): Map<String, String> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            TagOptionSingleton.getInstance().isAndroid = true
            val audioFile = resolveAudioFile(uriString, suffix, isTranscoded) { tempFile = it }
                ?: return@withContext emptyMap()

            val metadataMap = mutableMapOf<String, String>()
            appendAudioHeaderMetadata(audioFile, metadataMap)
            appendAudioTagMetadata(audioFile, metadataMap)
            metadataMap.filterValues { it.isNotEmpty() }
        } catch (e: CopyLimitException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyMap()
        } finally {
            tempFile?.delete()
        }
    }

    private suspend fun resolveAudioFile(
        uriString: String,
        suffix: String?,
        isTranscoded: Boolean,
        onTempFileCreated: (File) -> Unit
    ): AudioFile? {
        val directFile = File(FormatUtils.getFullPhysicalPath(uriString))
        if (directFile.exists() && directFile.canRead()) {
            try {
                return AudioFileIO.read(directFile)
            } catch (e: Exception) {
                if (e is CancellationException || !uriString.startsWith("content://")) throw e
            }
        }
        if (!uriString.startsWith("content://")) return null

        val ext = resolveExtension(uriString, suffix, isTranscoded)
        // 优先尝试基于文件描述符直读避免大文件整包拷入 cacheDir；若底层拒绝再回退临时文件
        readViaFileDescriptor(uriString, ext)?.let { return it }

        val uri = uriString.toUri()
        checkCopyBudget(declaredSize(uri) ?: 0L, context.cacheDir.usableSpace)
        val createdTemp = File.createTempFile("temp_tag_parsing", ".$ext", context.cacheDir)
        onTempFileCreated(createdTemp)
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            createdTemp.outputStream().use { output ->
                copyForTags(input, output) { context.cacheDir.usableSpace }
            }
        }
        currentCoroutineContext().ensureActive()
        return AudioFileIO.read(createdTemp)
    }

    internal suspend fun copyForTags(input: InputStream, output: OutputStream, availableSpace: () -> Long) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            checkCopyBudget(copied, availableSpace(), pendingBytes = count.toLong())
            output.write(buffer, 0, count)
        }
    }

    private fun declaredSize(uri: Uri): Long? {
        val resolver = context.contentResolver
        val size = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        if (size != null && size >= 0) return size
        return try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    internal fun checkCopyBudget(bytes: Long, free: Long, pendingBytes: Long = bytes) {
        if (bytes > MAX_TEMP_BYTES) throw CopyLimitException(CopyLimit.FILE_SIZE)
        if (pendingBytes > free - RESERVED_BYTES) throw CopyLimitException(CopyLimit.FREE_SPACE)
    }

    private fun appendAudioHeaderMetadata(audioFile: AudioFile, metadataMap: MutableMap<String, String>) {
        val header = audioFile.audioHeader
        if (header != null) {
            metadataMap["Encoding Format"] = header.format ?: ""
            metadataMap["Sample Rate"] = header.sampleRate?.let { "$it Hz" } ?: ""
            metadataMap["Channels"] = header.channels ?: ""
            metadataMap["BitRate (Internal)"] = header.bitRate?.let { "$it kbps" } ?: ""
        }
        val tag = audioFile.tag
        val formatStr = buildString {
            if (header != null) append(header.format ?: "")
            if (tag != null) {
                val tagType = tag.javaClass.simpleName.replace("Tag", "")
                if (isNotEmpty() && tagType.isNotEmpty()) append(" / ")
                append(tagType)
            }
        }
        if (formatStr.isNotEmpty()) {
            metadataMap[KEY_FORMAT_INFO] = formatStr
        }
    }

    private fun appendAudioTagMetadata(audioFile: AudioFile, metadataMap: MutableMap<String, String>) {
        val tag = audioFile.tag ?: return
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
                metadataMap["Raw: $key"] = value.replace(TEXT_CLEAN_REGEX, "").trim()
            }
        }
        metadataMap["Embedded Cover"] = if (tag.firstArtwork != null) "Yes" else "No"
    }

    private suspend fun resolveExtension(
        uriString: String,
        suffix: String?,
        isTranscoded: Boolean
    ): String {
        val decodedUri = Uri.decode(uriString)
        val uriExt = decodedUri.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it.isNotBlank() }
        return if (isTranscoded) {
            uriExt ?: preferencesManager.targetTranscodeFormat.first().lowercase()
        } else {
            suffix?.lowercase() ?: uriExt ?: "mp3"
        }
    }

    private fun readViaFileDescriptor(
        uriString: String,
        ext: String
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}
