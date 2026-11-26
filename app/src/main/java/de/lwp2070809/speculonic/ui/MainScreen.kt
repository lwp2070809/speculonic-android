package de.lwp2070809.speculonic.ui

import de.lwp2070809.speculonic.R
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.NavigationItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import de.lwp2070809.speculonic.ui.components.navigation.MainTopBar
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.ui.components.MiniPlayer
import de.lwp2070809.speculonic.ui.components.SyncDetailDialog
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.composition.CoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalDownloadController
import de.lwp2070809.speculonic.ui.composition.LocalMediaItemConverter
import de.lwp2070809.speculonic.ui.composition.LocalMiniPlayerBottomInset
import de.lwp2070809.speculonic.ui.composition.LocalNavigator
import de.lwp2070809.speculonic.ui.composition.LocalPlaybackController
import de.lwp2070809.speculonic.ui.composition.MediaItemConverter
import de.lwp2070809.speculonic.ui.navigation.AppNavDisplay
import de.lwp2070809.speculonic.ui.navigation.AppRoute
import de.lwp2070809.speculonic.ui.navigation.Navigator
import de.lwp2070809.speculonic.ui.navigation.rememberNavigationState
import de.lwp2070809.speculonic.ui.screens.player.NowPlayingViewModel
import de.lwp2070809.speculonic.ui.screens.search.SearchScreen
import de.lwp2070809.speculonic.ui.screens.search.SearchViewModel
import de.lwp2070809.speculonic.ui.screens.settings.ServerSyncUiState
import de.lwp2070809.speculonic.ui.screens.settings.ServerSyncViewModel
import de.lwp2070809.speculonic.ui.screens.settings.components.ServerConfigDialog
import de.lwp2070809.speculonic.util.toMediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    initialShowNowPlaying: Boolean = false,
    onNowPlayingShown: () -> Unit = {},
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit
) {
    val serverUrl by mainViewModel.serverUrl.collectAsStateWithLifecycle()

    val serverSyncViewModel: ServerSyncViewModel = hiltViewModel()
    val serverSyncUiState by serverSyncViewModel.uiState.collectAsStateWithLifecycle()
    val isSyncing by serverSyncViewModel.isSyncing.collectAsStateWithLifecycle()

    val currentContext = LocalContext.current
    val repository = mainViewModel.repository
    val stablePlaybackController = remember(mainViewModel) { mainViewModel.playbackController }
    val coverArtRequester = remember(repository, currentContext) {
        object : CoverArtRequester {
            override fun buildCoverArtRequest(
                id: String?,
                context: Context?,
                preferLocal: Boolean,
                size: Int?,
                crossfade: Boolean,
                fallbackUri: android.net.Uri?
            ): coil3.request.ImageRequest {
                return repository.buildCoverArtRequest(id, size, crossfade, fallbackUri)
            }
        }
    }
    val mediaItemConverter = remember(repository) {
        MediaItemConverter { song -> song.toMediaItem(repository) }
    }
    CompositionLocalProvider(
        LocalCoverArtRequester provides coverArtRequester,
        LocalMediaItemConverter provides mediaItemConverter,
        LocalDownloadController provides mainViewModel.downloadController,
        LocalPlaybackController provides stablePlaybackController
    ) {
        MainContent(
            downloadTracker = mainViewModel.downloadTracker,
            isOnline = isOnline,
            isEffectivelyOnline = isEffectivelyOnline,
            isStreamingAllowed = isStreamingAllowed,
            serverUrl = serverUrl,
            initialShowNowPlaying = initialShowNowPlaying,
            onNowPlayingShown = onNowPlayingShown,
            serverSyncViewModel = serverSyncViewModel,
            isSyncing = isSyncing,
            serverSyncUiState = serverSyncUiState,
            offlineMode = offlineMode,
            onToggleOfflineMode = onToggleOfflineMode
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MainContent(
    downloadTracker: DownloadTracker,
    isOnline: Boolean,
    isEffectivelyOnline: Boolean,
    isStreamingAllowed: Boolean,
    serverUrl: String?,
    initialShowNowPlaying: Boolean,
    onNowPlayingShown: () -> Unit,
    serverSyncViewModel: ServerSyncViewModel,
    isSyncing: Boolean,
    serverSyncUiState: ServerSyncUiState,
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit
) {
    val context = LocalContext.current
    val playbackController = LocalPlaybackController.current
    
    val topLevelRoutes = setOf(AppRoute.Discover, AppRoute.Library, AppRoute.Settings)
    val navigationState = rememberNavigationState(
        startRoute = AppRoute.Discover,
        topLevelRoutes = topLevelRoutes
    )
    val navigator = remember(navigationState, context) { Navigator(navigationState, context) }
    
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSyncDetailDialog by rememberSaveable { mutableStateOf(false) }
    val topBarState = remember { TopBarState() }

    LaunchedEffect(initialShowNowPlaying) {
        if (initialShowNowPlaying) {
            showNowPlaying = true
            onNowPlayingShown()
        }
    }

    var showServerSetupDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(serverUrl) {
        if (serverUrl != null) {
            showServerSetupDialog = serverUrl.isBlank()
        }
    }

    val nowPlayingViewModel: NowPlayingViewModel = hiltViewModel(key = "nowplaying_${serverUrl ?: ""}")

    data class NavItem(val route: AppRoute, @StringRes val title: Int, val icon: @Composable () -> Unit)
    val navItems = listOf(
        NavItem(AppRoute.Discover, R.string.discover, { Icon(Icons.Default.Home, contentDescription = null) }),
        NavItem(
            route = AppRoute.Library,
            title = R.string.library,
            icon = { Icon(painterResource(R.drawable.ic_symbol_library_music), contentDescription = null) }
        ),
        NavItem(AppRoute.Settings, R.string.settings, { Icon(Icons.Default.Settings, contentDescription = null) })
    )
    val currentRoute = navigationState.getRetainedKeys().lastOrNull()

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)
    val directive = remember(adaptiveInfo) {
        androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective(adaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listPaneWidth = directive.defaultPanePreferredWidth
    val isDualPane = directive.maxHorizontalPartitions > 1
    val downloadPageVisible = currentRoute == AppRoute.DownloadManager &&
        !showNowPlaying && (!showSearch || isDualPane)
    navigationState.isDualPane = isDualPane

    // 保持底层导航栏稳态计算，避免全屏浮层展开/折叠动画期间底层布局尺寸重算与导航栏闪烁抖动
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)

    val isExpandedRail = isExpanded && layoutType == NavigationSuiteType.NavigationRail

    val primaryColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val transparentColor = androidx.compose.ui.graphics.Color.Transparent
    
    val navItemColors = remember(primaryColor, unselectedColor) {
        NavigationItemColors(
            selectedIconColor = primaryColor,
            selectedTextColor = primaryColor,
            selectedIndicatorColor = transparentColor,
            unselectedIconColor = unselectedColor,
            unselectedTextColor = unselectedColor,
            disabledIconColor = unselectedColor.copy(alpha = 0.38f),
            disabledTextColor = unselectedColor.copy(alpha = 0.38f)
        )
    }

    val miniPlayerBottomInset = if (isExpanded) 96.dp else 0.dp

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalMiniPlayerBottomInset provides miniPlayerBottomInset
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavigationSuiteScaffold(
                modifier = Modifier.fillMaxSize(),
                navigationSuiteType = layoutType,
                navigationItemVerticalArrangement = if (isExpandedRail) Arrangement.Center else Arrangement.Top,
                navigationSuiteColors = NavigationSuiteDefaults.colors(
                    navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                navigationItems = {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = navigationState.topLevelRoute == item.route
                        NavigationSuiteItem(
                            icon = item.icon,
                            label = { Text(androidx.compose.ui.res.stringResource(item.title)) },
                            selected = isSelected,
                            onClick = { navigator.navigate(item.route) },
                            navigationSuiteType = layoutType,
                            colors = navItemColors
                        )
                        if (isExpandedRail && index < navItems.lastIndex) {
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            val activeDownloadsCount by downloadTracker.activeNonSilentCountFlow
                                .collectAsStateWithLifecycle(initialValue = 0)
                            MainTopBar(
                                currentRoute = currentRoute,
                                currentBackStack = navigationState.getCurrentBackStack(),
                                listPaneWidth = listPaneWidth,
                                topBarState = topBarState,
                                onBackClick = { navigator.goBack() },
                                onLeftBackClick = { navigator.goBackList() },
                                onSearchClick = { showSearch = true },
                                isSyncing = isSyncing,
                                syncError = serverSyncUiState.syncError,
                                onSyncStatusClick = { showSyncDetailDialog = true },
                                activeDownloadsCount = activeDownloadsCount,
                                onDownloadManagerClick = { navigator.navigate(AppRoute.DownloadManager) },
                                offlineMode = offlineMode,
                                onToggleOfflineMode = onToggleOfflineMode,
                                isDualPane = isDualPane
                            )
                        }
                    ) { innerPadding ->
                        if (isExpanded) {
                            Box(
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .fillMaxSize()
                            ) {
                                AppNavDisplay(
                                    navigator = navigator,
                                    navigationState = navigationState,
                                    topBarState = topBarState,
                                    isOnline = isOnline,
                                    isEffectivelyOnline = isEffectivelyOnline,
                                    isStreamingAllowed = isStreamingAllowed,
                                    serverSyncViewModel = serverSyncViewModel,
                                    directive = directive,
                                    downloadPageVisible = downloadPageVisible,
                                    modifier = Modifier.fillMaxSize()
                                )

                                AnimatedVisibility(
                                    visible = !showSearch,
                                    enter = fadeIn() + slideInVertically { it },
                                    exit = fadeOut() + slideOutVertically { it },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 24.dp)
                                        .padding(bottom = 16.dp)
                                        .navigationBarsPadding()
                                ) {
                                    MiniPlayer(
                                        onPlayPause = { playbackController.togglePlayPause() },
                                        onSkipPrevious = { playbackController.skipToPrevious() },
                                        onSkipNext = { playbackController.skipToNext() },
                                        onClick = { showNowPlaying = true },
                                        isFloating = true
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .padding(innerPadding)
                                    .fillMaxSize()
                            ) {
                                AppNavDisplay(
                                    navigator = navigator,
                                    navigationState = navigationState,
                                    topBarState = topBarState,
                                    isOnline = isOnline,
                                    isEffectivelyOnline = isEffectivelyOnline,
                                    isStreamingAllowed = isStreamingAllowed,
                                    serverSyncViewModel = serverSyncViewModel,
                                    directive = directive,
                                    downloadPageVisible = downloadPageVisible,
                                    modifier = Modifier.weight(1f)
                                )

                                MiniPlayer(
                                    onPlayPause = { playbackController.togglePlayPause() },
                                    onSkipPrevious = { playbackController.skipToPrevious() },
                                    onSkipNext = { playbackController.skipToNext() },
                                    onClick = { showNowPlaying = true },
                                    isFloating = false,
                                    modifier = Modifier.navigationBarsPadding()
                                )
                            }
                        }
                    }

                    SearchOverlay(
                        visible = showSearch,
                        isDualPane = isDualPane,
                        onClose = { showSearch = false },
                        modifier = if (isDualPane) Modifier.width(listPaneWidth + 1.dp).fillMaxHeight() else Modifier.fillMaxSize()
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Surface(
                                modifier = if (isDualPane) {
                                    Modifier
                                        .width(listPaneWidth)
                                        .fillMaxHeight()
                                        .pointerInput(Unit) {
                                            detectTapGestures { }
                                        }
                                } else {
                                    Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures { }
                                        }
                                },
                                color = MaterialTheme.colorScheme.background
                            ) {
                                SearchScreen(
                                    viewModel = hiltViewModel<SearchViewModel>(key = "search_${serverUrl ?: ""}"),
                                    isEffectivelyOnline = isEffectivelyOnline,
                                    isStreamingAllowed = isStreamingAllowed,
                                    onAlbumClick = { albumId ->
                                        navigator.navigate(AppRoute.AlbumDetail(albumId))
                                        if (!isDualPane) {
                                            showSearch = false
                                        }
                                    },
                                    onArtistClick = { artistId ->
                                        navigator.navigate(AppRoute.ArtistDetail(artistId))
                                        if (!isDualPane) {
                                            showSearch = false
                                        }
                                    },
                                    onClose = { showSearch = false }
                                )
                            }
                            if (isDualPane) {
                                androidx.compose.material3.VerticalDivider(
                                    modifier = Modifier.width(1.dp).fillMaxHeight(),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showNowPlaying,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                androidx.activity.compose.BackHandler(enabled = showNowPlaying) {
                    showNowPlaying = false
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { }
                        }
                ) {
                    de.lwp2070809.speculonic.ui.screens.player.NowPlayingScreen(
                        viewModel = nowPlayingViewModel,
                        isExpanded = isExpanded,
                        isEffectivelyOnline = isEffectivelyOnline,
                        isVisible = showNowPlaying,
                        onCollapse = { showNowPlaying = false }
                    )
                }
            }

            if (showSyncDetailDialog) {
                SyncDetailDialog(
                    onDismiss = { showSyncDetailDialog = false },
                    viewModel = serverSyncViewModel
                )
            }

            if (serverSyncUiState.showFirstSyncConfirm) {
                AlertDialog(
                    onDismissRequest = { serverSyncViewModel.cancelFirstSync() },
                    title = { Text(androidx.compose.ui.res.stringResource(R.string.first_sync_title)) },
                    text = { Text(androidx.compose.ui.res.stringResource(R.string.first_sync_message)) },
                    confirmButton = {
                        TextButton(onClick = { serverSyncViewModel.performFullSync() }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.sync_now))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { serverSyncViewModel.cancelFirstSync() }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.sync_later))
                        }
                    }
                )
            }

            if (serverSyncUiState.safetyGuardConfirmations.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { serverSyncViewModel.cancelSafetyGuard() },
                    title = { Text(androidx.compose.ui.res.stringResource(R.string.safety_guard_title)) },
                    text = {
                        Text(
                            serverSyncUiState.safetyGuardMessage
                                ?: stringResource(R.string.safety_guard_message)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { serverSyncViewModel.confirmSafetyGuard() }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.force_sync), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { serverSyncViewModel.cancelSafetyGuard() }) {
                            Text(androidx.compose.ui.res.stringResource(R.string.cancel))
                        }
                    }
                )
            }
            
            if (showServerSetupDialog) {
                ServerConfigDialog(
                    initialUrl = serverUrl ?: "",
                    initialUser = "",
                    initialPass = "",
                    initialAllowInsecure = serverSyncUiState.allowInsecureConnections,
                    viewModel = serverSyncViewModel,
                    showCancelButton = false,
                    onDismiss = { if (!serverUrl.isNullOrBlank()) showServerSetupDialog = false },
                    onSaved = { showServerSetupDialog = false }
                )
            }
        }
    }
}

@Composable
private fun SearchOverlay(
    visible: Boolean,
    isDualPane: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // Both the back gesture and the enter/exit animation use the same distance from the open position.
    val closedFraction = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) {
        closedFraction.animateTo(if (visible) 0f else 1f, tween(220))
    }

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    PredictiveBackHandler(enabled = visible && !keyboardVisible) { events ->
        try {
            closedFraction.stop()
            val start = closedFraction.value
            events.collect { event ->
                closedFraction.snapTo(start + (1f - start) * event.progress)
            }
            onClose()
        } catch (cancelled: CancellationException) {
            scope.launch { closedFraction.animateTo(0f, tween(180)) }
            throw cancelled
        }
    }

    if (visible || closedFraction.value < 1f) {
        Box(modifier.graphicsLayer {
            translationX = if (isDualPane) -size.width * closedFraction.value else 0f
            translationY = if (isDualPane) 0f else size.height * closedFraction.value
            alpha = 1f - closedFraction.value
        }) { content() }
    }
}
