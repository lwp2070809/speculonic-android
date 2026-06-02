package de.lwp2070809.speculonic.ui.components.navigation

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import de.lwp2070809.speculonic.ui.components.TopBarState
import de.lwp2070809.speculonic.ui.navigation.AppRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    currentRoute: NavKey?,
    topBarState: TopBarState,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val isTopLevel = currentRoute?.let { 
        it is AppRoute.Discover || 
        it is AppRoute.Library || 
        it is AppRoute.Settings
    } ?: false

    val isDefaultTopBarRoute = currentRoute?.let { 
        it is AppRoute.Discover || 
        it is AppRoute.Library || 
        it is AppRoute.Settings ||
        it is AppRoute.FavoriteSongs ||
        it is AppRoute.FavoriteAlbums
    } ?: false
    
    TopAppBar(
        title = { 
            val appName = "Speculo"
            val titleText = when {
                isTopLevel -> appName
                isDefaultTopBarRoute -> {
                    val titleRes = when (currentRoute) {
                        is AppRoute.FavoriteSongs -> R.string.favorite_songs
                        is AppRoute.FavoriteAlbums -> R.string.favorite_albums
                        else -> null
                    }
                    titleRes?.let { stringResource(it) } ?: ""
                }
                topBarState.title.isNotEmpty() -> topBarState.title
                else -> ""
            }

            if (titleText.isNotEmpty()) {
                if (isTopLevel && titleText == appName) {
                    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val logoSpacing = if (isLandscape) 24.dp else 8.dp
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(logoSpacing)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = appName,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
