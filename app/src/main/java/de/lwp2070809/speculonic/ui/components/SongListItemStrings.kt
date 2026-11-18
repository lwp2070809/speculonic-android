package de.lwp2070809.speculonic.ui.components

import androidx.compose.runtime.Immutable

/**
 * 歌曲列表项高频文本资源包装类，避免大列表快速滚动时子项内部重复查询 CompositionLocal。
 */
@Immutable
data class SongListItemStrings(
    val successMsg: String,
    val existsMsg: String,
    val errorMsg: String,
    val cdDownloaded: String,
    val cdStar: String,
    val moreOptions: String,
    val download: String,
    val removeDownload: String,
    val songDetails: String,
    val addToPlaylist: String,
    val removeFromPlaylist: String,
    val incompatibleDownloadedTemplate: String,
    val unsupportedFormatTemplate: String
) {
    fun formatIncompatibleDownloaded(suffix: String): String =
        String.format(incompatibleDownloadedTemplate, suffix)

    fun formatUnsupportedFormat(suffix: String): String =
        String.format(unsupportedFormatTemplate, suffix)
}
