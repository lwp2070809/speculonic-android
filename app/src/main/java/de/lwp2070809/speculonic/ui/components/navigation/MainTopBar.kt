package de.lwp2070809.speculonic.ui.components.navigation

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.navigation.AppRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    currentRoute: NavKey?,
    topBarState: TopBarState,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit,
    isSyncing: Boolean = false,
    syncProgress: String? = null,
    syncError: String? = null,
    onSyncStatusClick: () -> Unit = {},
    activeDownloadsCount: Int = 0,
    onDownloadManagerClick: () -> Unit = {},
    offlineMode: Boolean,
    onToggleOfflineMode: () -> Unit
) {
    val appRoute = currentRoute as? AppRoute
    val isTopLevel = appRoute?.isTopLevel ?: false
    val isDefaultTopBarRoute = appRoute?.isDefaultTopBar ?: false
    
    val scope = rememberCoroutineScope()
    var showWifiHighlightRecent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var wifiHighlightJob by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    TopAppBar(
        title = { 
            val appName = "Speculo"
            val titleText = when {
                isTopLevel -> appName
                isDefaultTopBarRoute -> {
                    val titleRes = appRoute.defaultTitleRes
                    titleRes?.let { stringResource(it) } ?: ""
                }
                topBarState.title.isNotEmpty() -> topBarState.title
                else -> ""
            }

            if (titleText.isNotEmpty()) {
                if (isTopLevel) {
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val logoSpacing = if (isLandscape) 24.dp else 8.dp
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(logoSpacing)
                    ) {
                        Text(
                            text = appName,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        var showCloudDoneRecent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        var lastSyncingState by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                        androidx.compose.runtime.LaunchedEffect(isSyncing) {
                            if (lastSyncingState && !isSyncing) {
                                showCloudDoneRecent = true
                                kotlinx.coroutines.delay(5000)
                                showCloudDoneRecent = false
                            }
                            lastSyncingState = isSyncing
                        }

                        IconButton(
                            onClick = onSyncStatusClick,
                            modifier = Modifier
                                .align(Alignment.Bottom)
                                .size(34.dp)
                        ) {
                            CloudSyncIcon(
                                isSyncing = isSyncing,
                                showCloudDoneRecent = showCloudDoneRecent,
                                hasError = syncError != null
                            )
                        }

                        IconButton(
                            onClick = {
                                wifiHighlightJob?.cancel()
                                wifiHighlightJob = scope.launch {
                                    showWifiHighlightRecent = true
                                    onToggleOfflineMode()
                                    kotlinx.coroutines.delay(3000)
                                    showWifiHighlightRecent = false
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.Bottom)
                                .size(34.dp)
                        ) {
                            WifiOfflineIcon(
                                offlineMode = offlineMode,
                                isTriggeredRecent = showWifiHighlightRecent
                            )
                        }

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
                            androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
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
                            androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                        }

                        IconButton(
                            onClick = onDownloadManagerClick,
                            modifier = Modifier
                                .align(Alignment.Bottom)
                                .offset(y = (-1).dp)
                                .size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
                                if (activeDownloadsCount > 0) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_arrow_downward),
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
                                        painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_arrow_downward),
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
            val shouldShowBack = if (isTopLevel) false 
                                 else if (!isDefaultTopBarRoute) (topBarState.showBack ?: (currentRoute != null))
                                 else true
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
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                }
            }
        }
    )
}

@Composable
private fun CloudSyncIcon(
    isSyncing: Boolean,
    showCloudDoneRecent: Boolean,
    hasError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val targetAlpha = if (isSyncing || showCloudDoneRecent) 1f else 0.4f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    val syncingAlpha by animateFloatAsState(
        targetValue = if (isSyncing) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = 0
        ),
        label = "syncingAlpha"
    )
    val doneAlpha by animateFloatAsState(
        targetValue = if (isSyncing || hasError) 0f else 1f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = if (isSyncing) 0 else 500
        ),
        label = "doneAlpha"
    )

    val errorAlpha by animateFloatAsState(
        targetValue = if (!isSyncing && hasError) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            delayMillis = if (isSyncing) 0 else 500
        ),
        label = "errorAlpha"
    )

    Box(
        modifier = modifier.alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.alpha(doneAlpha)) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_cloud_done),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }

        Box(modifier = Modifier.alpha(syncingAlpha)) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_cloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )

            val rotationState = if (isSyncing) {
                val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
                infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )
            } else {
                androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            }

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
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_sync),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(syncIconSize)
                        .rotate(rotationState.value)
                )
            }
        }

        Box(modifier = Modifier.alpha(errorAlpha)) {
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
private fun WifiOfflineIcon(
    offlineMode: Boolean,
    isTriggeredRecent: Boolean,
    modifier: Modifier = Modifier
) {
    val targetAlpha = if (isTriggeredRecent) 1f else 0.4f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 500),
        label = "alpha"
    )

    Icon(
        painter = if (offlineMode) androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi_off) else androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_wifi),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .size(26.dp)
            .alpha(alpha)
    )
}
