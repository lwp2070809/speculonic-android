package de.lwp2070809.speculonic

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import de.lwp2070809.speculonic.network.model.Song
import de.lwp2070809.speculonic.ui.components.SongListItem
import de.lwp2070809.speculonic.ui.components.rememberSongListItemStrings
import de.lwp2070809.speculonic.ui.theme.FormFactorPreviews
import de.lwp2070809.speculonic.ui.theme.SpeculonicTheme

/**
 * 原生 Compose Preview 跨设备形态（手机、折叠屏、平板、桌面）截图测试。
 * 由 AGP screenshotTests 原生插件自动发现并生成多分辨率像素级 Golden 基准。
 */
class FormFactorPreviewsScreenshotTest {

    @Preview(showBackground = true)
    @FormFactorPreviews
    @Composable
    fun SongListItemMultiDeviceScreenshotPreview() {
        SpeculonicTheme {
            val sampleSong = Song(
                id = "screenshot-test-song-1",
                title = "Speculonic Golden Anthem",
                artist = "Subsonic Sound Engineer",
                duration = 248,
                starred = "2026-11-03T00:00:00Z"
            )
            SongListItem(
                song = sampleSong,
                isCurrent = false,
                isOnline = true,
                isEffectivelyOnline = true,
                isStreamingAllowed = true,
                onClick = {},
                onStarClick = {},
                strings = rememberSongListItemStrings()
            )
        }
    }
}
