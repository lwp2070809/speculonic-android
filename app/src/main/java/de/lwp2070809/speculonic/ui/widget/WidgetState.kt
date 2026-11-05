package de.lwp2070809.speculonic.ui.widget

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Speculonic 桌面微件 Glance Preferences 状态存储键常量与轻量数据模型
 */
object WidgetStateKeys {
    val KEY_SONG_ID = stringPreferencesKey("song_id")
    val KEY_TITLE = stringPreferencesKey("title")
    val KEY_ARTIST = stringPreferencesKey("artist")
    val KEY_ALBUM = stringPreferencesKey("album")
    val KEY_ARTWORK_URI = stringPreferencesKey("artwork_uri")
    val KEY_IS_PLAYING = booleanPreferencesKey("is_playing")
    val KEY_HAS_TRACK = booleanPreferencesKey("has_track")
    val KEY_BACKGROUND_ALPHA = floatPreferencesKey("background_alpha")
}

data class MusicWidgetData(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String = "",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
    val backgroundAlpha: Float = 0.85f
)
