package de.lwp2070809.speculonic.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Discover : AppRoute
    
    @Serializable
    data object Library : AppRoute
    
    @Serializable
    data object Settings : AppRoute
    
    @Serializable data object SettingsServer : AppRoute
    @Serializable data object SettingsPlayback : AppRoute
    @Serializable data object SettingsAppearance : AppRoute
    @Serializable data object SettingsStorage : AppRoute
    @Serializable data object SettingsNetwork : AppRoute
    @Serializable data object SettingsAdvanced : AppRoute
    @Serializable data object SettingsAbout : AppRoute
    
    @Serializable
    data class AlbumDetail(val albumId: String) : AppRoute
    
    @Serializable
    data class PlaylistDetail(val playlistId: String) : AppRoute
    
    @Serializable
    data class ArtistDetail(val artistId: String) : AppRoute
    
    @Serializable
    data object FavoriteSongs : AppRoute
    
    @Serializable
    data object FavoriteAlbums : AppRoute
}
