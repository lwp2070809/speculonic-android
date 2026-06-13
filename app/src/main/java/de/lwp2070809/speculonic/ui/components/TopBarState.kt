package de.lwp2070809.speculonic.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class TopBarStateData(
    val title: String,
    val actions: @Composable RowScope.() -> Unit,
    val onBackClickOverride: (() -> Unit)?,
    val showSearch: Boolean,
    val showBack: Boolean?,
    val token: String
)

class TopBarState {
    private val stateStack = mutableListOf<TopBarStateData>()

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
        val newState = TopBarStateData(title, actions, onBackClickOverride, showSearch, showBack, token)
        stateStack.removeAll { it.token == token && token.isNotEmpty() }
        stateStack.add(newState)
        applyState(newState)
    }

    fun clear(token: String) {
        if (token.isNotEmpty()) {
            stateStack.removeAll { it.token == token }
            val nextState = stateStack.lastOrNull()
            if (nextState != null) {
                applyState(nextState)
            } else {
                applyState(TopBarStateData("", {}, null, true, null, ""))
            }
        }
    }

    private fun applyState(state: TopBarStateData) {
        this.title = state.title
        this.actions = state.actions
        this.onBackClickOverride = state.onBackClickOverride
        this.showSearch = state.showSearch
        this.showBack = state.showBack
        this.currentToken = state.token
    }
}
