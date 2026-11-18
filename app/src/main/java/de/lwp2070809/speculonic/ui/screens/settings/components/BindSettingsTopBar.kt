package de.lwp2070809.speculonic.ui.screens.settings.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import de.lwp2070809.speculonic.ui.components.TopBarState
import java.util.UUID

@Composable
fun BindSettingsTopBar(
    topBarState: TopBarState,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    showSearch: Boolean = false,
    showBack: Boolean = true
) {
    val screenToken = remember { UUID.randomUUID().toString() }
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(title, showSearch, showBack) {
        topBarState.update(
            title = title,
            actions = { currentActions() },
            showSearch = showSearch,
            showBack = showBack,
            token = screenToken
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }
}
