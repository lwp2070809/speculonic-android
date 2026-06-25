package de.lwp2070809.speculonic.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.ui.screens.details.AlbumDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.AlbumDetailViewModel
import de.lwp2070809.speculonic.ui.screens.details.ArtistDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.ArtistDetailViewModel
import de.lwp2070809.speculonic.ui.screens.details.PlaylistDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.PlaylistDetailViewModel
import de.lwp2070809.speculonic.ui.screens.discover.DiscoverScreen
import de.lwp2070809.speculonic.ui.screens.discover.DiscoverViewModel
import de.lwp2070809.speculonic.ui.screens.library.AlbumGrid
import de.lwp2070809.speculonic.ui.screens.library.FavoriteList
import de.lwp2070809.speculonic.ui.screens.library.LibraryScreen
import de.lwp2070809.speculonic.ui.screens.library.LibraryViewModel
import de.lwp2070809.speculonic.ui.screens.settings.SettingsScreen
import de.lwp2070809.speculonic.ui.screens.settings.SettingsViewModel

import de.lwp2070809.speculonic.ui.screens.settings.StorageCacheSettings
import de.lwp2070809.speculonic.ui.screens.download.DownloadManagerScreen

@Composable
fun AppNavDisplay(
    navigator: Navigator,
    navigationState: NavigationState,
    topBarState: TopBarState,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    onShowSearch: () -> Unit,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    
    
    val discoverViewModel: DiscoverViewModel = hiltViewModel()
    val libraryViewModel: LibraryViewModel = hiltViewModel()

    val myTransitionSpec: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(300)
        ) + fadeIn(animationSpec = tween(300)) togetherWith slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))
    }

    val myPopTransitionSpec: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(300)
        ) + fadeIn(animationSpec = tween(300)) togetherWith slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(300)
        ) + fadeOut(animationSpec = tween(300))
    }

    val entryProvider: (NavKey) -> androidx.navigation3.runtime.NavEntry<NavKey> = entryProvider {
        entry<AppRoute.Discover> {
            DiscoverScreen(
                viewModel = discoverViewModel,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                onViewAllFavoriteSongs = { navigator.navigate(AppRoute.FavoriteSongs) },
                onViewAllFavoriteAlbums = { navigator.navigate(AppRoute.FavoriteAlbums) },
                onAlbumClick = { navigator.navigate(AppRoute.AlbumDetail(it)) },
                onPlaylistClick = { navigator.navigate(AppRoute.PlaylistDetail(it)) },
                onConfigureServerClick = { navigator.navigate(AppRoute.Settings) }
            )
        }

        entry<AppRoute.FavoriteSongs> {
            val uiState by libraryViewModel.uiState.collectAsState()
            val downloadedIds by DownloadTracker.downloadedSongIds.collectAsState()
            val context = LocalContext.current
            val repository = LocalSubsonicRepository.current
            val downloadController = remember(repository) { DownloadController(context, repository) }

            FavoriteList(
                songs = uiState.favorites,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                onRefresh = { libraryViewModel.refreshFavorites() },
                isLoading = uiState.isRefreshing,
                onDownloadClick = { downloadController.downloadSong(it) },
                onDownloadAllClick = {
                    uiState.favorites.forEach { song ->
                        val isDownloaded = song.isFullyCached
                        if (!isDownloaded) {
                            downloadController.downloadSong(song)
                        }
                    }
                }
            )
        }

        entry<AppRoute.FavoriteAlbums> {
            val uiState by discoverViewModel.uiState.collectAsState()
            AlbumGrid(
                albums = uiState.favoriteAlbums,
                onAlbumClick = { albumId -> navigator.navigate(AppRoute.AlbumDetail(albumId)) },
                isLoading = uiState.isLoading,
                onRefresh = { if (isOnline) discoverViewModel.refreshFavoriteAlbums() }
            )
        }

        entry<AppRoute.Library> {
            LibraryScreen(
                viewModel = libraryViewModel,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                onAlbumClick = { navigator.navigate(AppRoute.AlbumDetail(it)) },
                onArtistClick = { navigator.navigate(AppRoute.ArtistDetail(it)) },
                onPlaylistClick = { navigator.navigate(AppRoute.PlaylistDetail(it)) }
            )
        }

        entry<AppRoute.Settings> {
            
            SettingsScreen(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                onNavigate = { navigator.navigate(it) }
            )
        }

        entry<AppRoute.SettingsServer> {
            
            de.lwp2070809.speculonic.ui.screens.settings.ServerSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                isEffectivelyOnline = isEffectivelyOnline
            )
        }

        entry<AppRoute.SettingsPlayback> {
            
            de.lwp2070809.speculonic.ui.screens.settings.PlaybackSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAppearance> {
            
            de.lwp2070809.speculonic.ui.screens.settings.AppearanceSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }



        entry<AppRoute.StorageCacheSettings> {
            StorageCacheSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                isEffectivelyOnline = isEffectivelyOnline
            )
        }

        entry<AppRoute.DownloadManager> {
            DownloadManagerScreen(
                topBarState = topBarState,
                onBackClick = { navigator.goBack() }
            )
        }

        entry<AppRoute.SettingsNetwork> {
            
            de.lwp2070809.speculonic.ui.screens.settings.NetworkSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAdvanced> {
            
            de.lwp2070809.speculonic.ui.screens.settings.AdvancedSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsBluetooth> {
            de.lwp2070809.speculonic.ui.screens.settings.BluetoothSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAbout> {
            
            de.lwp2070809.speculonic.ui.screens.settings.AboutSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                onBackClick = { navigator.goBack() }
            )
        }

        entry<AppRoute.AlbumDetail> { route ->
            val viewModel: AlbumDetailViewModel = hiltViewModel<AlbumDetailViewModel, AlbumDetailViewModel.Factory>(
                key = "album_${route.albumId}",
                creationCallback = { factory -> factory.create(route.albumId) }
            )
            AlbumDetailScreen(
                viewModel = viewModel,
                topBarState = topBarState,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                onBackClick = { navigator.goBack() },
                onSearchClick = onShowSearch
            )
        }

        entry<AppRoute.ArtistDetail> { route ->
            val viewModel: ArtistDetailViewModel = hiltViewModel<ArtistDetailViewModel, ArtistDetailViewModel.Factory>(
                key = "artist_${route.artistId}",
                creationCallback = { factory -> factory.create(route.artistId) }
            )
            ArtistDetailScreen(
                viewModel = viewModel,
                topBarState = topBarState,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                onAlbumClick = { navigator.navigate(AppRoute.AlbumDetail(it)) },
                onBackClick = { navigator.goBack() },
                onSearchClick = onShowSearch
            )
        }

        entry<AppRoute.PlaylistDetail> { route ->
            val viewModel: PlaylistDetailViewModel = hiltViewModel<PlaylistDetailViewModel, PlaylistDetailViewModel.Factory>(
                key = "playlist_${route.playlistId}",
                creationCallback = { factory -> factory.create(route.playlistId) }
            )
            PlaylistDetailScreen(
                viewModel = viewModel,
                topBarState = topBarState,
                isOnline = isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                onBackClick = { navigator.goBack() },
                onSearchClick = onShowSearch
            )
        }
    }

    val safeEntryProvider: (NavKey) -> androidx.navigation3.runtime.NavEntry<NavKey> = { key ->
        try {
            entryProvider(key)
        } catch (e: Exception) {
            androidx.navigation3.runtime.NavEntry(key) { _ ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(de.lwp2070809.speculonic.R.string.route_not_found, key))
                }
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    NavDisplay(
        backStack = navigationState.getRetainedKeys(),
        onBack = {
            val stack = navigationState.backStacks[navigationState.topLevelRoute] ?: emptyList()
            if (stack.size > 1) {
                navigator.goBack()
            } else {
                if (navigationState.topLevelRoute != AppRoute.Discover) {
                    navigator.navigate(AppRoute.Discover)
                } else {
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            remember { ClickBlockerNavEntryDecorator() }
        ),
        transitionSpec = myTransitionSpec,
        popTransitionSpec = myPopTransitionSpec,
        entryProvider = safeEntryProvider
    )
}


private class ClickBlockerNavEntryDecorator<T : Any> : NavEntryDecorator<T>(
    decorate = { entry ->
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState(initial = lifecycleOwner.lifecycle.currentState)
        val isTransitioning = lifecycleState < Lifecycle.State.RESUMED
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val currentState = lifecycleOwner.lifecycle.currentState
                            
                            
                            if (currentState < Lifecycle.State.RESUMED && event.type == PointerEventType.Press) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .semantics {
                    if (isTransitioning) {
                        onClick(action = { true })
                    }
                }
        ) {
            entry.Content()
        }
    }
)
