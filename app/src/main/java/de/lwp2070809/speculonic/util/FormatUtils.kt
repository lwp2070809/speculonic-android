package de.lwp2070809.speculonic.util

import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object FormatUtils {
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val MILLIS_PER_SECOND = 1000L
    private const val BYTE_UNIT_BASE = 1024.0
    private const val TREE_PATH_PREFIX = "/tree/"
    private const val RAW_DOC_PREFIX = "raw:"
    private const val PRIMARY_DOC_PREFIX = "primary:"

    fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "00:00"
        val mins = seconds / SECONDS_PER_MINUTE
        val secs = seconds % SECONDS_PER_MINUTE
        return if (mins >= MINUTES_PER_HOUR) {
            val hrs = mins / MINUTES_PER_HOUR
            val remainingMins = mins % MINUTES_PER_HOUR
            String.format(Locale.getDefault(), "%d:%02d:%02d", hrs, remainingMins, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", mins, secs)
        }
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "00:00"
        return formatDuration((millis / MILLIS_PER_SECOND).toInt())
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(BYTE_UNIT_BASE))
            .toInt()
            .coerceAtMost(units.size - 1)
        return String.format(
            Locale.getDefault(),
            "%.2f %s",
            bytes / BYTE_UNIT_BASE.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun simplifySafUri(uriString: String): String {
        if (uriString.isEmpty()) return ""
        return try {
            val path = uriString.toUri().path ?: return uriString
            if (!path.startsWith(TREE_PATH_PREFIX)) {
                return Uri.decode(uriString)
            }
            val docId = path.removePrefix(TREE_PATH_PREFIX)
            val lastSlash = if (docId.startsWith(RAW_DOC_PREFIX)) docId.lastIndexOf('/') else -1
            if (lastSlash != -1) {
                docId.substring(lastSlash + 1)
            } else {
                Uri.decode(docId)
            }
        } catch (e: Exception) {
            LogManager.w("FormatUtils: Failed to simplify SAF URI", e)
            uriString
        }
    }

    fun getFullPhysicalPath(uriString: String): String {
        if (uriString.isEmpty()) return ""
        return try {
            val uri = uriString.toUri()
            if (uri.scheme == "file") {
                return Uri.decode(uri.path ?: uriString)
            }
            val path = uri.path ?: return Uri.decode(uriString)
            val docId = when {
                path.contains("/document/") -> path.substringAfterLast("/document/")
                path.startsWith(TREE_PATH_PREFIX) -> path.removePrefix(TREE_PATH_PREFIX)
                else -> path
            }
            if (docId.startsWith(RAW_DOC_PREFIX)) {
                return Uri.decode(docId.removePrefix(RAW_DOC_PREFIX))
            }
            val decoded = Uri.decode(docId)
            if (decoded.startsWith(PRIMARY_DOC_PREFIX)) {
                // SAF primary: documentId 对应主外部共享存储根目录，此处无 Context 可用
                @Suppress("DEPRECATION")
                val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                return "$primaryRoot/${decoded.removePrefix(PRIMARY_DOC_PREFIX)}"
            }
            decoded
        } catch (e: Exception) {
            LogManager.w("FormatUtils: Failed to resolve full physical path", e)
            Uri.decode(uriString)
        }
    }

    val SUPPORTED_AUDIO_EXTENSIONS: List<String>
        get() = MediaFormatUtils.ALL_SUPPORTED_AUDIO_EXTENSIONS.toList()

    fun isSupportedAudioFile(fileName: String): Boolean {
        return MediaFormatUtils.isSupportedAudioFile(fileName)
    }

    fun replaceExtensionWithLrc(fileName: String): String {
        val lastSep = maxOf(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'))
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > lastSep + 1) {
            fileName.substring(0, lastDotIndex) + ".lrc"
        } else {
            "$fileName.lrc"
        }
    }

    fun getMimeTypeFromExtension(extension: String?): String {
        return MediaFormatUtils.getMimeTypeFromExtension(extension)
    }

    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        if (text.length * 3 <= maxBytes || text.encodeToByteArray().size <= maxBytes) return text
        var endIndex = 0
        var usedBytes = 0
        while (endIndex < text.length) {
            val codePoint = text.codePointAt(endIndex)
            val charCount = Character.charCount(codePoint)
            val cpBytes = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (usedBytes + cpBytes > maxBytes) break
            usedBytes += cpBytes
            endIndex += charCount
        }
        return text.substring(0, endIndex)
    }
}
