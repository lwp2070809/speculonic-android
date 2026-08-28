package de.lwp2070809.speculonic.util

import de.lwp2070809.speculonic.network.model.Song
import java.util.Locale
import java.util.regex.Pattern

/**
 * 媒体音频格式兼容性与白名单工具类
 * 集中管理 ExoPlayer 原生支持的音频容器白名单、全量支持的音频扩展名集合、
 * 缓存文件解析正则以及结合服务端转码偏好的综合可播性判定。
 */
object MediaFormatUtils {

    /**
     * 歌词文件后缀名
     */
    const val EXTENSION_LRC = "lrc"

    /**
     * ExoPlayer 原生直接支持解封装和解码的音频扩展名白名单（小写）
     */
    val DIRECT_PLAYBACK_SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "mp3",
        "flac",
        "m4a",
        "aac",
        "ogg",
        "opus",
        "oga",
        "wav",
        "webm",
        "mka",
        "amr",
        "awb"
    )

    /**
     * 应用支持扫描、识别、导出及转码的所有音频扩展名白名单集合（小写）
     */
    val ALL_SUPPORTED_AUDIO_EXTENSIONS: Set<String> = DIRECT_PLAYBACK_SUPPORTED_EXTENSIONS + setOf(
        "alac",
        "aiff",
        "dsf",
        "ape",
        "wv",
        "wma",
        "mpc"
    )

    /**
     * 可供用户选择的服务端转码目标格式
     */
    val SUPPORTED_TRANSCODE_FORMATS: List<String> = listOf("mp3", "flac", "opus")

    /**
     * 用于从缓存/导出的音频和歌词文件名中提取歌曲 ID 的统一正则表达式
     * 匹配格式: "Artist - Title [songId].ext"
     */
    val CACHE_FILE_ID_PATTERN: Pattern = Pattern.compile(
        ".*\\[(.+)\\]\\.(${ALL_SUPPORTED_AUDIO_EXTENSIONS.joinToString("|")}|$EXTENSION_LRC)$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * 从缓存文件名中提取歌曲 ID
     *
     * @param fileName 文件名（例如 "Artist - Title [song123].mp3"）
     * @return 成功提取则返回 songId，否则返回 null
     */
    fun extractSongIdFromFileName(fileName: String): String? {
        val matcher = CACHE_FILE_ID_PATTERN.matcher(fileName)
        return if (matcher.matches()) matcher.group(1) else null
    }

    /**
     * 判断指定文件名是否为支持的音频文件（不含歌词文件）
     */
    fun isSupportedAudioFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return ALL_SUPPORTED_AUDIO_EXTENSIONS.contains(extension)
    }

    /**
     * 判断指定扩展名是否属于受支持的音频扩展名
     */
    fun isSupportedAudioExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        val clean = extension.trim().lowercase(Locale.getDefault()).removePrefix(".")
        return ALL_SUPPORTED_AUDIO_EXTENSIONS.contains(clean)
    }

    /**
     * 判断指定后缀名的音频文件是否可被当前设备的 ExoPlayer 原生直接解码播放
     */
    fun isDirectPlaybackSupported(suffix: String?): Boolean {
        if (suffix.isNullOrBlank()) return true // 缺省时默认尝试播放
        val cleanSuffix = suffix.trim().lowercase(Locale.getDefault()).removePrefix(".")
        return DIRECT_PLAYBACK_SUPPORTED_EXTENSIONS.contains(cleanSuffix)
    }

    /**
     * 综合判定某首歌曲在当前设置下是否可播放
     *
     * @param song 待判定的单曲对象
     * @param transcodeIncompatible 是否开启了服务端转码不兼容格式选项
     * @return 若可播放返回 true；若需在 UI 层置灰并拦截点击返回 false
     */
    fun isSongPlayable(song: Song, transcodeIncompatible: Boolean): Boolean {
        val isDirectSupported = isDirectPlaybackSupported(song.suffix)
        if (isDirectSupported) {
            return true
        }

        // 对于原生不支持的格式：
        // 1. 如果本地已缓存：必须是已转码文件（isTranscoded == true）才可播放；未转码的 Raw 不兼容文件本地读取必定解码失败
        if (song.isFullyCached) {
            return song.isTranscoded
        }

        // 2. 如果本地未缓存：取决于是否开启了服务端转码
        return transcodeIncompatible
    }

    /**
     * 根据音频扩展名获取标准 MIME 类型
     */
    fun getMimeTypeFromExtension(extension: String?): String {
        return when (extension?.trim()?.lowercase(Locale.getDefault())?.removePrefix(".")) {
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "m4a", "aac", "alac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aiff" -> "audio/x-aiff"
            "dsf" -> "audio/x-dsf"
            "ape" -> "audio/x-ape"
            "wv" -> "audio/x-wavpack"
            "wma" -> "audio/x-ms-wma"
            "mpc" -> "audio/x-musepack"
            "amr" -> "audio/amr"
            "awb" -> "audio/amr-wb"
            "webm" -> "audio/webm"
            "mka" -> "audio/x-matroska"
            "mp3" -> "audio/mpeg"
            else -> "audio/mpeg"
        }
    }
}
