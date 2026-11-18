package de.lwp2070809.speculonic.ui.components.navigation

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.navigation.AdaptiveNavigationDefaults
import de.lwp2070809.speculonic.ui.navigation.AppRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val APP_BAR_BRAND_NAME = "Speculo"
private const val CLOUD_DONE_HIGHLIGHT_MS = 5000L
private const val WIFI_HIGHLIGHT_MS = 3000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    currentRoute: NavKey?,
    currentBackStack: List<NavKey> = emptyList(),
    listPaneWidth: Dp = AdaptiveNavigationDefaults.ListPanePreferredWidth,
    topBarState: TopBarState,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit,
    isSyncing: Boolean = false,
    syncError: String? = null,
    onSyncStatusClick: () -> Unit = {},
    activeDownloadsCount: Int = 0,
    onDownloadManagerClick: () -> Unit = {},
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit,
    isDualPane: Boolean = false,
    onLeftBackClick: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var showWifiHighlightRecent by remember { mutableStateOf(false) }
    var wifiHighlightJob by remember { mutableStateOf<Job?>(null) }
    val showCloudDoneRecent = rememberCloudDoneRecent(isSyncing)

    val triggerOfflineToggle = remember(onToggleOfflineMode) {
        {
            wifiHighlightJob?.cancel()
            wifiHighlightJob = scope.launch {
                showWifiHighlightRecent = true
                onToggleOfflineMode()
                delay(WIFI_HIGHLIGHT_MS)
                showWifiHighlightRecent = false
            }
            Unit
        }
    }

    if (isDualPane) {
        DualPaneTopBar(
            currentRoute = currentRoute,
            currentBackStack = currentBackStack,
            listPaneWidth = listPaneWidth,
            topBarState = topBarState,
            onBackClick = onBackClick,
            onLeftBackClick = onLeftBackClick,
            onSearchClick = onSearchClick,
            isSyncing = isSyncing,
            showCloudDoneRecent = showCloudDoneRecent,
            syncError = syncError,
            onSyncStatusClick = onSyncStatusClick,
            activeDownloadsCount = activeDownloadsCount,
            onDownloadManagerClick = onDownloadManagerClick,
            offlineMode = offlineMode,
            showWifiHighlightRecent = showWifiHighlightRecent,
            onToggleOfflineClick = triggerOfflineToggle
        )
    } else {
        SinglePaneTopBar(
            currentRoute = currentRoute,
            topBarState = topBarState,
            onBackClick = onBackClick,
            onSearchClick = onSearchClick,
            isSyncing = isSyncing,
            showCloudDoneRecent = showCloudDoneRecent,
            syncError = syncError,
            onSyncStatusClick = onSyncStatusClick,
            activeDownloadsCount = activeDownloadsCount,
            onDownloadManagerClick = onDownloadManagerClick,
            offlineMode = offlineMode,
            showWifiHighlightRecent = showWifiHighlightRecent,
            onToggleOfflineClick = triggerOfflineToggle
        )
    }
}

@Composable
private fun rememberCloudDoneRecent(isSyncing: Boolean): Boolean {
    var showCloudDoneRecent by remember { mutableStateOf(false) }
    var lastSyncingState by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncing) {
        if (lastSyncingState && !isSyncing) {
            showCloudDoneRecent = true
            delay(CLOUD_DONE_HIGHLIGHT_MS)
            showCloudDoneRecent = false
        }
        lastSyncingState = isSyncing
    }
    return showCloudDoneRecent
}

@Composable
private fun TopBarActionControls(
    isSyncing: Boolean,
    showCloudDoneRecent: Boolean,
    syncError: String?,
    onSyncStatusClick: () -> Unit,
    offlineMode: Boolean,
    showWifiHighlightRecent: Boolean,
    onToggleOfflineClick: () -> Unit,
    activeDownloadsCount: Int,
    onDownloadManagerClick: () -> Unit
) {
    IconButton(
        onClick = onSyncStatusClick,
        modifier = Modifier.size(34.dp)
    ) {
        CloudSyncIcon(
            isSyncing = isSyncing,
            showCloudDoneRecent = showCloudDoneRecent,
            hasError = syncError != null
        )
    }
    IconButton(
        onClick = onToggleOfflineClick,
        modifier = Modifier.size(34.dp)
    ) {
        WifiOfflineIcon(
            offlineMode = offlineMode,
            isTriggeredRecent = showWifiHighlightRecent
        )
    }
    DownloadBounceIconButton(
        activeDownloadsCount = activeDownloadsCount,
        onClick = onDownloadManagerClick,
        modifier = Modifier.offset(y = (-1).dp)
    )
}

@Composable
private fun TopBarSearchButton(
    onSearchClick: () -> Unit,
    compact: Boolean
) {
    IconButton(
        onClick = onSearchClick,
        modifier = if (compact) {
            Modifier
                .size(34.dp)
                .offset(y = 2.dp)
        } else {
            Modifier
        }
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.search),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DualPaneTopBar(
    currentRoute: NavKey?,
    currentBackStack: List<NavKey>,
    listPaneWidth: Dp,
    topBarState: TopBarState,
    onBackClick: () -> Unit,
    onLeftBackClick: (() -> Unit)?,
    onSearchClick: () -> Unit,
    isSyncing: Boolean,
    showCloudDoneRecent: Boolean,
    syncError: String?,
    onSyncStatusClick: () -> Unit,
    activeDownloadsCount: Int,
    onDownloadManagerClick: () -> Unit,
    offlineMode: Boolean,
    showWifiHighlightRecent: Boolean,
    onToggleOfflineClick: () -> Unit
) {
    val activeListRoute = currentBackStack
        .filterIsInstance<AppRoute>()
        .lastOrNull { it.isListPane }
        ?: (currentRoute as? AppRoute)?.takeIf { it.isListPane }
        ?: AppRoute.Discover

    val activeDetailRoute = currentBackStack
        .filterIsInstance<AppRoute>()
        .lastOrNull { it.isDetailPane }

    val showLeftBack = !activeListRoute.isTopLevel
    val leftTitle = if (showLeftBack) {
        activeListRoute.defaultTitleRes?.let { stringResource(it) } ?: APP_BAR_BRAND_NAME
    } else {
        APP_BAR_BRAND_NAME
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧半区：动态对齐 ListPane 首选宽度
            Row(
                modifier = Modifier
                    .width(listPaneWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val verticalAlignment = if (showLeftBack) Alignment.CenterVertically else Alignment.Bottom
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = verticalAlignment,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showLeftBack) {
                        IconButton(
                            onClick = onLeftBackClick ?: onBackClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = leftTitle,
                        fontSize = if (showLeftBack) 22.sp else 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    TopBarActionControls(
                        isSyncing = isSyncing,
                        showCloudDoneRecent = showCloudDoneRecent,
                        syncError = syncError,
                        onSyncStatusClick = onSyncStatusClick,
                        offlineMode = offlineMode,
                        showWifiHighlightRecent = showWifiHighlightRecent,
                        onToggleOfflineClick = onToggleOfflineClick,
                        activeDownloadsCount = activeDownloadsCount,
                        onDownloadManagerClick = onDownloadManagerClick
                    )
                }

                if (activeListRoute != AppRoute.Settings) {
                    TopBarSearchButton(onSearchClick = onSearchClick, compact = true)
                }
            }

            VerticalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 右侧半区：对齐 DetailPane
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val detailTitle = when {
                    activeDetailRoute == null -> ""
                    activeDetailRoute.isDefaultTopBar -> activeDetailRoute.defaultTitleRes?.let { stringResource(it) } ?: ""
                    else -> topBarState.title
                }

                Text(
                    text = detailTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeDetailRoute != null && !activeDetailRoute.isDefaultTopBar) {
                            topBarState.actions(this)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SinglePaneTopBar(
    currentRoute: NavKey?,
    topBarState: TopBarState,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit,
    isSyncing: Boolean,
    showCloudDoneRecent: Boolean,
    syncError: String?,
    onSyncStatusClick: () -> Unit,
    activeDownloadsCount: Int,
    onDownloadManagerClick: () -> Unit,
    offlineMode: Boolean,
    showWifiHighlightRecent: Boolean,
    onToggleOfflineClick: () -> Unit
) {
    val appRoute = currentRoute as? AppRoute
    val isTopLevel = appRoute?.isTopLevel ?: false
    val isDefaultTopBarRoute = appRoute?.isDefaultTopBar ?: false
    val titleText = when {
        isTopLevel -> APP_BAR_BRAND_NAME
        isDefaultTopBarRoute -> appRoute.defaultTitleRes?.let { stringResource(it) } ?: ""
        topBarState.title.isNotEmpty() -> topBarState.title
        else -> ""
    }

    TopAppBar(
        title = {
            if (titleText.isNotEmpty()) {
                if (isTopLevel) {
                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 24.dp else 8.dp)
                    ) {
                        Text(
                            text = APP_BAR_BRAND_NAME,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TopBarActionControls(
                            isSyncing = isSyncing,
                            showCloudDoneRecent = showCloudDoneRecent,
                            syncError = syncError,
                            onSyncStatusClick = onSyncStatusClick,
                            offlineMode = offlineMode,
                            showWifiHighlightRecent = showWifiHighlightRecent,
                            onToggleOfflineClick = onToggleOfflineClick,
                            activeDownloadsCount = activeDownloadsCount,
                            onDownloadManagerClick = onDownloadManagerClick
                        )
                    }
                } else {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        navigationIcon = {
            val shouldShowBack = when {
                isTopLevel -> false
                !isDefaultTopBarRoute -> topBarState.showBack ?: (currentRoute != null)
                else -> true
            }
            if (shouldShowBack) {
                IconButton(onClick = {
                    if (!isDefaultTopBarRoute && topBarState.onBackClickOverride != null) {
                        topBarState.onBackClickOverride?.invoke()
                    } else {
                        onBackClick()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
        },
        actions = {
            if (!isDefaultTopBarRoute) {
                topBarState.actions(this)
            }
            if (topBarState.showSearch) {
                TopBarSearchButton(onSearchClick = onSearchClick, compact = isTopLevel)
            }
        }
    )
}

@Composable
private fun DownloadBounceIconButton(
    activeDownloadsCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val translationY = if (activeDownloadsCount > 0) {
        val infiniteTransition = rememberInfiniteTransition(label = "download_bounce")
        infiniteTransition.animateFloat(
            initialValue = -4f,
            targetValue = 4f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "translationY"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val alpha = if (activeDownloadsCount > 0) {
        val infiniteTransition = rememberInfiniteTransition(label = "download_fade")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "alpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    IconButton(
        onClick = onClick,
        modifier = modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
            if (activeDownloadsCount > 0) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_symbol_arrow_downward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            this.translationY = translationY.value * density
                            this.alpha = alpha.value
                        }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.5.dp)
                        .size(width = 16.dp, height = 2.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_symbol_arrow_downward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(width = 16.dp, height = 2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape = CircleShape)
                )
            }
        }
    }
}

@Composable
private fun CloudSyncIcon(
    isSyncing: Boolean,
    showCloudDoneRecent: Boolean,
    modifier: Modifier = Modifier,
    hasError: Boolean = false
) {
    val targetAlpha = if (isSyncing || showCloudDoneRecent) 1f else 0.4f
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    val syncingAlphaState = animateFloatAsState(
        targetValue = if (isSyncing) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = 0
        ),
        label = "syncingAlpha"
    )
    val doneAlphaState = animateFloatAsState(
        targetValue = if (isSyncing || hasError) 0f else 1f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = if (isSyncing) 0 else 500
        ),
        label = "doneAlpha"
    )

    val errorAlphaState = animateFloatAsState(
        targetValue = if (!isSyncing && hasError) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = if (isSyncing) 0 else 500
        ),
        label = "errorAlpha"
    )

    Box(
        modifier = modifier.graphicsLayer { alpha = alphaState.value },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.graphicsLayer { alpha = doneAlphaState.value }) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_cloud_done),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        Box(modifier = Modifier.graphicsLayer { alpha = syncingAlphaState.value }) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_cloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )

            val syncIconOffsetX = 0.5.dp
            val syncIconOffsetY = 0.dp
            val syncIconSize = 15.dp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = syncIconOffsetX, y = syncIconOffsetY)
                    .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                    .padding(1.dp)
            ) {
                if (isSyncing) {
                    SpinningCloudSyncBadgeIcon(modifier = Modifier.size(syncIconSize))
                } else {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_sync),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(syncIconSize)
                    )
                }
            }
        }

        Box(modifier = Modifier.graphicsLayer { alpha = errorAlphaState.value }) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_cloud_alert),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun SpinningCloudSyncBadgeIcon(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotationState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    Icon(
        painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_sync),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.graphicsLayer { rotationZ = rotationState.value }
    )
}

@Composable
private fun WifiOfflineIcon(
    offlineMode: Boolean,
    isTriggeredRecent: Boolean,
    modifier: Modifier = Modifier
) {
    val targetAlpha = if (isTriggeredRecent) 1f else 0.4f
    val alphaState = animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    Icon(
        painter = if (offlineMode) {
            androidx.compose.ui.res.painterResource(
                id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi_off
            )
        } else {
            androidx.compose.ui.res.painterResource(
                id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi
            )
        },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(26.dp)
            .graphicsLayer { alpha = alphaState.value }
    )
}
