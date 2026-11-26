package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel

/** Retains the open dialog's password across configuration changes, never in saved state. */
class ServerConfigDraftViewModel : ViewModel() {
    private var passwordDraft: TextFieldState? = null

    fun password(initialPassword: String): TextFieldState =
        passwordDraft ?: TextFieldState(initialPassword).also { passwordDraft = it }

    fun discardDraft() {
        passwordDraft = null
    }

    override fun onCleared() {
        discardDraft()
    }
}
