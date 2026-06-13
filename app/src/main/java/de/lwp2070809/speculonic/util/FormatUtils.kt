package de.lwp2070809.speculonic.util

import android.net.Uri
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object FormatUtils {
    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 60) {
            val hrs = mins / 60
            val remainingMins = mins % 60
            String.format(Locale.getDefault(), "%d:%02d:%02d", hrs, remainingMins, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", mins, secs)
        }
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0:00"
        return formatDuration((millis / 1000).toInt())
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.size - 1)
        return String.format(
            Locale.getDefault(),
            "%.2f %s",
            bytes / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    fun simplifySafUri(uriString: String): String {
        if (uriString.isEmpty()) return ""
        try {
            val uri = Uri.parse(uriString)
            val path = uri.path ?: return uriString
            if (path.startsWith("/tree/")) {
                val docId = path.substring(6)
                if (docId.startsWith("raw:")) {
                    val lastSlash = docId.lastIndexOf('/')
                    if (lastSlash != -1) {
                        return docId.substring(lastSlash + 1)
                    }
                }
                return Uri.decode(docId) 
            }
            return Uri.decode(uriString)
        } catch (e: Exception) {
            return uriString
        }
    }

    fun getFullPhysicalPath(uriString: String): String {
        if (uriString.isEmpty()) return ""
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                return Uri.decode(uri.path ?: uriString)
            }
            
            val path = uri.path ?: return Uri.decode(uriString)
            
            val docId = if (path.contains("/document/")) {
                path.substringAfterLast("/document/")
            } else if (path.startsWith("/tree/")) {
                path.substring(6)
            } else {
                path
            }
            
            if (docId.startsWith("raw:")) {
                return Uri.decode(docId.substring(4))
            }
            
            val decoded = Uri.decode(docId)
            
            if (decoded.startsWith("primary:")) {
                return "/storage/emulated/0/" + decoded.substring(8)
            }
            return decoded
        } catch (e: Exception) {
            return Uri.decode(uriString)
        }
    }

    val SUPPORTED_AUDIO_EXTENSIONS = listOf(
        "mp3", "flac", "m4a", "wav", "aac", "ogg", "alac", "aiff", "dsf"
    )

    fun isSupportedAudioFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return SUPPORTED_AUDIO_EXTENSIONS.contains(extension)
    }

    fun replaceExtensionWithLrc(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex != -1) {
            fileName.substring(0, lastDotIndex) + ".lrc"
        } else {
            "$fileName.lrc"
        }
    }

    fun getMimeTypeFromExtension(extension: String?): String {
        return when (extension?.lowercase(Locale.getDefault())) {
            "flac" -> "audio/flac"
            "m4a", "aac", "alac" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aiff" -> "audio/x-aiff"
            "dsf" -> "audio/x-dsf"
            else -> "audio/mpeg" 
        }
    }
}
