package de.lwp2070809.speculonic.ui.components

import androidx.compose.runtime.Composable

// F-Droid handles updates, so this distribution has no in-app changelog dialog.
@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdateChangelogDialog(
    version: String,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit
) = Unit
