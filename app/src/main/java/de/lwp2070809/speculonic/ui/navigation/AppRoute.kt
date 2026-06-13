package de.lwp2070809.speculonic.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import de.lwp2070809.speculonic.R

@Serializable
sealed interface AppRoute : NavKey {
    val isTopLevel: Boolean get() = false
    val isDefaultTopBar: Boolean get() = false
    val defaultTitleRes: Int? get() = null

    @Serializable
    data object Discover : AppRoute {
        override val isTopLevel: Boolean get() = true
        override val isDefaultTopBar: Boolean get() = true
    }
    
    @Serializable
    data object Library : AppRoute {
        override val isTopLevel: Boolean get() = true
        override val isDefaultTopBar: Boolean get() = true
    }
    
    @Serializable
    data object Settings : AppRoute {
        override val isTopLevel: Boolean get() = true
        override val isDefaultTopBar: Boolean get() = true
    }
    
    @Serializable data object SettingsServer : AppRoute
    @Serializable data object SettingsPlayback : AppRoute
    @Serializable data object SettingsAppearance : AppRoute
    @Serializable data object SettingsStorage : AppRoute
    @Serializable data object SettingsNetwork : AppRoute
    @Serializable data object SettingsAdvanced : AppRoute
    @Serializable data object SettingsAbout : AppRoute
    @Serializable data object SettingsBluetooth : AppRoute
    
    @Serializable
    data class AlbumDetail(val albumId: String) : AppRoute
    
    @Serializable
    data class PlaylistDetail(val playlistId: String) : AppRoute
    
    @Serializable
    data class ArtistDetail(val artistId: String) : AppRoute
    
    @Serializable
    data object FavoriteSongs : AppRoute {
        override val isDefaultTopBar: Boolean get() = true
        override val defaultTitleRes: Int? get() = R.string.favorite_songs
    }
    
    @Serializable
    data object FavoriteAlbums : AppRoute {
        override val isDefaultTopBar: Boolean get() = true
        override val defaultTitleRes: Int? get() = R.string.favorite_albums
    }
}
