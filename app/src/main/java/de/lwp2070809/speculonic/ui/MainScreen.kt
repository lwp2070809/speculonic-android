package de.lwp2070809.speculonic.ui

import de.lwp2070809.speculonic.R
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.ui.components.navigation.MainTopBar
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.ui.components.MiniPlayer
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import de.lwp2070809.speculonic.ui.navigation.AppNavDisplay
import de.lwp2070809.speculonic.ui.navigation.AppRoute
import de.lwp2070809.speculonic.ui.navigation.Navigator
import de.lwp2070809.speculonic.ui.navigation.rememberNavigationState
import de.lwp2070809.speculonic.ui.screens.player.NowPlayingViewModel
import de.lwp2070809.speculonic.ui.screens.search.SearchScreen
import de.lwp2070809.speculonic.ui.screens.search.SearchViewModel
import de.lwp2070809.speculonic.ui.screens.settings.components.ServerConfigDialog
import de.lwp2070809.speculonic.ui.screens.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    preferencesManager: PreferencesManager,
    playbackController: PlaybackController,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    repository: SubsonicRepository,
    initialShowNowPlaying: Boolean = false,
    onNowPlayingShown: () -> Unit = {},
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit
) {
    val serverUrl by preferencesManager.serverUrl.collectAsState(initial = preferencesManager.getServerUrlSync())
    val username by preferencesManager.username.collectAsState(initial = "")
    val password by preferencesManager.password.collectAsState(initial = "")

    val settingsViewModel: de.lwp2070809.speculonic.ui.screens.settings.SettingsViewModel = hiltViewModel()
    val settingsUiState by settingsViewModel.uiState.collectAsState()
    val isSyncing by settingsViewModel.isSyncing.collectAsState()

    val stableRepository = remember(repository) { repository }
    val stablePlaybackController = remember(playbackController) { playbackController }

    CompositionLocalProvider(
        LocalSubsonicRepository provides stableRepository,
        LocalPlaybackController provides stablePlaybackController
    ) {
        MainContent(
            windowSizeClass = windowSizeClass,
            preferencesManager = preferencesManager,
            isOnline = isOnline,
            isEffectivelyOnline = isEffectivelyOnline,
            isStreamingAllowed = isStreamingAllowed,
            serverUrl = serverUrl,
            initialShowNowPlaying = initialShowNowPlaying,
            onNowPlayingShown = onNowPlayingShown,
            settingsViewModel = settingsViewModel,
            isSyncing = isSyncing,
            settingsUiState = settingsUiState,
            offlineMode = offlineMode,
            onToggleOfflineMode = onToggleOfflineMode
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MainContent(
    windowSizeClass: WindowSizeClass,
    preferencesManager: PreferencesManager,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    serverUrl: String?,
    initialShowNowPlaying: Boolean,
    onNowPlayingShown: () -> Unit,
    settingsViewModel: SettingsViewModel,
    isSyncing: Boolean,
    settingsUiState: de.lwp2070809.speculonic.ui.screens.settings.SettingsUiState,
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit
) {
    val context = LocalContext.current
    val repository = LocalSubsonicRepository.current
    val playbackController = LocalPlaybackController.current
    

    
    val topLevelRoutes = setOf(AppRoute.Discover, AppRoute.Library, AppRoute.Settings)
    val navigationState = rememberNavigationState(
        startRoute = AppRoute.Discover,
        topLevelRoutes = topLevelRoutes
    )
    val navigator = remember(navigationState, context) { Navigator(navigationState, context) }
    
    var showNowPlaying by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSyncDetailDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val topBarState = remember { TopBarState() }

    LaunchedEffect(initialShowNowPlaying) {
        if (initialShowNowPlaying) {
            showNowPlaying = true
            onNowPlayingShown()
        }
    }


    
    
    

    var showServerSetupDialog by remember { mutableStateOf(false) }
    LaunchedEffect(serverUrl) {
        if (serverUrl.isNullOrBlank()) {
            showServerSetupDialog = true
        }
    }

    LaunchedEffect(repository) {
        if (!serverUrl.isNullOrBlank()) {
            repository.ping()
        }
    }

    
    val allDownloads by DownloadTracker.allDownloadsFlow.collectAsState()
    val searchViewModel: SearchViewModel = hiltViewModel(key = "search_$serverUrl")
    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel(key = "nowplaying_$serverUrl")

    data class NavItem(val route: AppRoute, @StringRes val title: Int, val icon: ImageVector)
    val navItems = listOf(
        NavItem(AppRoute.Discover, R.string.discover, Icons.Default.Home),
        NavItem(AppRoute.Library, R.string.library, Icons.Default.LibraryMusic),
        NavItem(AppRoute.Settings, R.string.settings, Icons.Default.Settings)
    )
    val currentRoute = navigationState.getRetainedKeys().lastOrNull()



    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    
    val primaryColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val transparentColor = androidx.compose.ui.graphics.Color.Transparent
    
    val customItemColors = androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = primaryColor,
            selectedTextColor = primaryColor,
            indicatorColor = transparentColor,
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = primaryColor,
            selectedTextColor = primaryColor,
            indicatorColor = transparentColor,
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                val activeDownloadsCount = remember(allDownloads) {
                    allDownloads.count { info ->
                        val isSilent = try {
                            if (info.task.request.data.isNotEmpty()) {
                                val json = org.json.JSONObject(androidx.media3.common.util.Util.fromUtf8Bytes(info.task.request.data))
                                json.optBoolean("isSilent", false)
                            } else false
                        } catch (e: Exception) {
                            false
                        }
                        !isSilent && (info.state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING || info.state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED)
                    }
                }
                MainTopBar(
                    currentRoute = currentRoute,
                    topBarState = topBarState,
                    onBackClick = { navigator.goBack() },
                    onSearchClick = { showSearch = true },
                    isSyncing = isSyncing,
                    syncProgress = settingsUiState.syncProgress,
                    syncError = settingsUiState.syncError,
                    onSyncStatusClick = { showSyncDetailDialog = true },
                    activeDownloadsCount = activeDownloadsCount,
                    onDownloadManagerClick = { navigator.navigate(AppRoute.DownloadManager) },
                    offlineMode = offlineMode,
                    onToggleOfflineMode = onToggleOfflineMode
                )
            }
        ) { innerPadding ->
            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                navigationSuiteColors = androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults.colors(
                    navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                navigationSuiteItems = {
                    navItems.forEach { item ->
                        val isSelected = navigationState.topLevelRoute == item.route
                        item(
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(androidx.compose.ui.res.stringResource(item.title)) },
                            selected = isSelected,
                            onClick = { navigator.navigate(item.route) },
                            colors = customItemColors
                        )
                    }
                }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AppNavDisplay(
                        navigator = navigator,
                        navigationState = navigationState,
                        topBarState = topBarState,
                        isOnline = isOnline,
                        isEffectivelyOnline = isEffectivelyOnline,
                        isStreamingAllowed = isStreamingAllowed,
                        onShowSearch = { showSearch = true },
                        settingsViewModel = settingsViewModel,
                        modifier = Modifier.weight(1f)
                    )
                    
                    MiniPlayer(
                        onPlayPause = { playbackController.togglePlayPause() },
                        onSkipPrevious = { playbackController.skipToPrevious() },
                        onSkipNext = { playbackController.skipToNext() },
                        onClick = { showNowPlaying = true },
                        modifier = Modifier.navigationBarsPadding()
                    )
                }
            }
        }

        
        AnimatedVisibility(
            visible = showSearch,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                SearchScreen(
                    viewModel = searchViewModel,
                    isOnline = isOnline,
                    isEffectivelyOnline = isEffectivelyOnline,
                    isStreamingAllowed = isStreamingAllowed,
                    onAlbumClick = { albumId ->
                        navigator.navigate(AppRoute.AlbumDetail(albumId))
                        showSearch = false
                    },
                    onArtistClick = { artistId ->
                        navigator.navigate(AppRoute.ArtistDetail(artistId))
                        showSearch = false
                    },
                    onClose = { showSearch = false }
                )
            }
        }

        
        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                androidx.activity.compose.BackHandler {
                    showNowPlaying = false
                }
                de.lwp2070809.speculonic.ui.screens.player.NowPlayingScreen(
                    viewModel = nowPlayingViewModel,
                    isExpanded = isExpanded,
                    isEffectivelyOnline = isEffectivelyOnline,
                    onCollapse = { showNowPlaying = false }
                )
            }
        }

        
        if (showSyncDetailDialog) {
            de.lwp2070809.speculonic.ui.components.SyncDetailDialog(
                onDismiss = { showSyncDetailDialog = false },
                viewModel = settingsViewModel
            )
        }

        
        if (settingsUiState.showFirstSyncConfirm) {
            AlertDialog(
                onDismissRequest = { settingsViewModel.cancelFirstSync() },
                title = { Text(androidx.compose.ui.res.stringResource(R.string.first_sync_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(R.string.first_sync_message)) },
                confirmButton = {
                    TextButton(onClick = { settingsViewModel.performFullSync() }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.sync_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.cancelFirstSync() }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.sync_later))
                    }
                }
            )
        }

        if (settingsUiState.showSafetyGuardConfirm) {
            AlertDialog(
                onDismissRequest = { settingsViewModel.cancelSafetyGuard() },
                title = { Text(androidx.compose.ui.res.stringResource(R.string.safety_guard_title)) },
                text = { Text(settingsUiState.safetyGuardMessage ?: androidx.compose.ui.res.stringResource(R.string.safety_guard_message)) },
                confirmButton = {
                    TextButton(onClick = { settingsViewModel.confirmSafetyGuard() }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.force_sync), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.cancelSafetyGuard() }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.cancel))
                    }
                }
            )
        }
        
        if (showServerSetupDialog) {
            ServerConfigDialog(
                initialUrl = "",
                initialUser = "",
                initialPass = "",
                viewModel = settingsViewModel,
                showCancelButton = false,
                onDismiss = { if (!serverUrl.isNullOrBlank()) showServerSetupDialog = false },
                onSave = { url, user, pass, syncCoverArt ->
                    settingsViewModel.updateServerUrl(url)
                    settingsViewModel.updateUsername(user)
                    settingsViewModel.updatePassword(pass)
                    settingsViewModel.saveSettings(syncCoverArt)
                    showServerSetupDialog = false
                }
            )
        }
    }

}
