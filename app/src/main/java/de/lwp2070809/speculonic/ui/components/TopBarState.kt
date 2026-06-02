package de.lwp2070809.speculonic.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class TopBarState {
    var title by mutableStateOf("")
    var actions by mutableStateOf<@Composable RowScope.() -> Unit>({})
    var onBackClickOverride by mutableStateOf<(() -> Unit)?>(null)
    var showSearch by mutableStateOf(true)
    var showBack by mutableStateOf<Boolean?>(null) 
    
    var currentToken by mutableStateOf("")

    fun update(
        title: String, 
        actions: @Composable RowScope.() -> Unit = {},
        onBackClickOverride: (() -> Unit)? = null,
        showSearch: Boolean = true,
        showBack: Boolean? = null,
        token: String = ""
    ) {
        this.title = title
        this.actions = actions
        this.onBackClickOverride = onBackClickOverride
        this.showSearch = showSearch
        this.showBack = showBack
        this.currentToken = token
    }

    fun clear(token: String) {
        if (this.currentToken == token && token.isNotEmpty()) {
            update("", {}, null, true, null, "")
        }
    }
}
