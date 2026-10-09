package de.lwp2070809.speculonic.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.screens.details.AlbumDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.AlbumDetailViewModel
import de.lwp2070809.speculonic.ui.screens.details.ArtistDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.ArtistDetailViewModel
import de.lwp2070809.speculonic.ui.screens.details.PlaylistDetailScreen
import de.lwp2070809.speculonic.ui.screens.details.PlaylistDetailViewModel
import de.lwp2070809.speculonic.ui.screens.discover.DiscoverScreen
import de.lwp2070809.speculonic.ui.screens.discover.DiscoverViewModel
import de.lwp2070809.speculonic.ui.screens.download.DownloadManagerScreen
import de.lwp2070809.speculonic.ui.screens.library.AlbumGrid
import de.lwp2070809.speculonic.ui.screens.library.FavoriteList
import de.lwp2070809.speculonic.ui.screens.library.LibraryScreen
import de.lwp2070809.speculonic.ui.screens.library.LibraryViewModel
import de.lwp2070809.speculonic.ui.screens.settings.AboutSettings
import de.lwp2070809.speculonic.ui.screens.settings.AdvancedSettings
import de.lwp2070809.speculonic.ui.screens.settings.AppearanceSettings
import de.lwp2070809.speculonic.ui.screens.settings.BluetoothSettings
import de.lwp2070809.speculonic.ui.screens.settings.NetworkSettings
import de.lwp2070809.speculonic.ui.screens.settings.PlaybackSettings
import de.lwp2070809.speculonic.ui.screens.settings.ServerSettings
import de.lwp2070809.speculonic.ui.screens.settings.SettingsScreen
import de.lwp2070809.speculonic.ui.screens.settings.SettingsViewModel
import de.lwp2070809.speculonic.ui.screens.settings.StorageCacheSettings

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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

    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

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
        entry<AppRoute.Discover>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    PanePlaceholder(
                        icon = { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
                        title = stringResource(R.string.placeholder_detail_title),
                        subtitle = stringResource(R.string.placeholder_detail_subtitle)
                    )
                }
            )
        ) {
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

        entry<AppRoute.FavoriteSongs>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    PanePlaceholder(
                        icon = { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
                        title = stringResource(R.string.placeholder_detail_title),
                        subtitle = stringResource(R.string.placeholder_detail_subtitle)
                    )
                }
            )
        ) {
            val uiState by libraryViewModel.uiState.collectAsState()
            val downloadController = LocalDownloadController.current
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
                        if (!song.isFullyCached) {
                            downloadController.downloadSong(song)
                        }
                    }
                },
                onStarClick = { songId, star, onResult ->
                    libraryViewModel.toggleStarSong(songId, star, onResult)
                }
            )
        }

        entry<AppRoute.FavoriteAlbums>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    PanePlaceholder(
                        icon = { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
                        title = stringResource(R.string.placeholder_detail_title),
                        subtitle = stringResource(R.string.placeholder_detail_subtitle)
                    )
                }
            )
        ) {
            val uiState by discoverViewModel.uiState.collectAsState()
            AlbumGrid(
                albums = uiState.favoriteAlbums,
                onAlbumClick = { albumId -> navigator.navigate(AppRoute.AlbumDetail(albumId)) },
                isLoading = uiState.isLoading,
                onRefresh = { if (isOnline) discoverViewModel.refreshFavoriteAlbums() }
            )
        }

        entry<AppRoute.Library>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    PanePlaceholder(
                        icon = { Icon(painterResource(R.drawable.ic_symbol_library_music), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
                        title = stringResource(R.string.placeholder_detail_title),
                        subtitle = stringResource(R.string.placeholder_detail_subtitle)
                    )
                }
            )
        ) {
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

        entry<AppRoute.Settings>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    PanePlaceholder(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
                        title = stringResource(R.string.placeholder_settings_title),
                        subtitle = stringResource(R.string.placeholder_settings_subtitle)
                    )
                }
            )
        ) {
            SettingsScreen(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                onNavigate = { navigator.navigate(it) }
            )
        }

        entry<AppRoute.SettingsServer>(metadata = ListDetailSceneStrategy.detailPane()) {
            ServerSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                isEffectivelyOnline = isEffectivelyOnline
            )
        }

        entry<AppRoute.SettingsPlayback>(metadata = ListDetailSceneStrategy.detailPane()) {
            PlaybackSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAppearance>(metadata = ListDetailSceneStrategy.detailPane()) {
            AppearanceSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.StorageCacheSettings>(metadata = ListDetailSceneStrategy.detailPane()) {
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

        entry<AppRoute.SettingsNetwork>(metadata = ListDetailSceneStrategy.detailPane()) {
            NetworkSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAdvanced>(metadata = ListDetailSceneStrategy.detailPane()) {
            AdvancedSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsBluetooth>(metadata = ListDetailSceneStrategy.detailPane()) {
            BluetoothSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState
            )
        }

        entry<AppRoute.SettingsAbout>(metadata = ListDetailSceneStrategy.detailPane()) {
            AboutSettings(
                viewModel = settingsViewModel,
                topBarState = topBarState,
                isEffectivelyOnline = isEffectivelyOnline,
                onBackClick = { navigator.goBack() }
            )
        }

        entry<AppRoute.AlbumDetail>(metadata = ListDetailSceneStrategy.detailPane()) { route ->
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

        entry<AppRoute.ArtistDetail>(metadata = ListDetailSceneStrategy.detailPane()) { route ->
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

        entry<AppRoute.PlaylistDetail>(metadata = ListDetailSceneStrategy.detailPane()) { route ->
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

        entry<AppRoute.PlaybackQueue>(metadata = ListDetailSceneStrategy.detailPane()) {
            val playbackController = de.lwp2070809.speculonic.ui.composition.LocalPlaybackController.current
            de.lwp2070809.speculonic.ui.screens.player.PlaybackQueuePane(
                playbackController = playbackController,
                onBackClick = { navigator.goBack() }
            )
        }
    }

    val safeEntryProvider: (NavKey) -> androidx.navigation3.runtime.NavEntry<NavKey> = { key ->
        try {
            entryProvider(key)
        } catch (e: Exception) {
            androidx.navigation3.runtime.NavEntry(key) { _ ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.route_not_found, key))
                }
            }
        }
    }

    val isDualPane = directive.maxHorizontalPartitions > 1
    androidx.compose.runtime.CompositionLocalProvider(de.lwp2070809.speculonic.ui.composition.LocalIsDualPane provides isDualPane) {
        NavDisplay(
            backStack = navigationState.getCurrentBackStack(),
            onBack = { navigator.goBack() },
            sceneStrategies = listOf(listDetailStrategy),
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
}

@Composable
private fun PanePlaceholder(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
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
