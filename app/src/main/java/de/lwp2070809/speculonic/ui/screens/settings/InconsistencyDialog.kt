package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.domain.usecase.ResolveInconsistencyUseCase.Action

@Composable
fun InconsistencyDialog(
    inconsistentItems: List<InconsistentItem>,
    isResolving: Boolean,
    resolutionMessage: String?,
    onDismissRequest: () -> Unit,
    onResolve: (Action, InconsistentItem?) -> Unit
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val deletableCount = inconsistentItems.count { it.canDelete }
    Dialog(
        onDismissRequest = { if (!isResolving) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = SettingsConstants.DIALOG_TONAL_ELEVATION
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(SettingsConstants.DIALOG_PADDING)) {
                    Text(
                        text = stringResource(R.string.inconsistency_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_MEDIUM))
                    if (inconsistentItems.isEmpty() && resolutionMessage == null) {
                        Text(
                            text = stringResource(R.string.inconsistency_dialog_success),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_SMALL))
                        Text(
                            text = stringResource(R.string.inconsistency_dialog_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (inconsistentItems.isNotEmpty()) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.inconsistency_dialog_found,
                                inconsistentItems.size,
                                inconsistentItems.size
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (resolutionMessage != null) {
                        Text(
                            text = resolutionMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (inconsistentItems.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(
                            items = inconsistentItems,
                            key = { it.key },
                            contentType = { "inconsistent_item" }
                        ) { item ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = item.displayTitle,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    val reason = when (item.type) {
                                        InconsistentItem.Type.MISSING_FILE -> stringResource(R.string.inconsistency_reason_missing)
                                        InconsistentItem.Type.BINARY_MISMATCH -> stringResource(R.string.inconsistency_reason_mismatch)
                                        InconsistentItem.Type.ORPHANED_FILE -> stringResource(R.string.inconsistency_reason_orphaned)
                                        InconsistentItem.Type.AWAITING_REDOWNLOAD -> stringResource(R.string.cache_awaiting_redownload)
                                    }
                                    Column {
                                        Text(text = reason, color = MaterialTheme.colorScheme.error)
                                        FlowRow {
                                            if (item.canDelete) {
                                                TextButton(enabled = !isResolving, onClick = { onResolve(Action.DELETE, item) }) {
                                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                            if (item.canRedownload) {
                                                TextButton(enabled = !isResolving, onClick = { onResolve(Action.REDOWNLOAD, item) }) {
                                                    Text(stringResource(R.string.redownload))
                                                }
                                            }
                                        }
                                    }
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (inconsistentItems.isNotEmpty()) {
                        TextButton(enabled = !isResolving && deletableCount > 0, onClick = { confirmDeleteAll = true }) {
                            Text(stringResource(R.string.cache_delete_all))
                        }
                        TextButton(enabled = !isResolving && inconsistentItems.any { it.canRedownload },
                            onClick = { onResolve(Action.REDOWNLOAD, null) }
                        ) { Text(stringResource(R.string.cache_redownload_all)) }
                    }
                    TextButton(enabled = !isResolving, onClick = onDismissRequest) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.cache_delete_all)) },
            text = {
                Text(pluralStringResource(R.plurals.cache_confirm_delete_all, deletableCount, deletableCount))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onResolve(Action.DELETE, null)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
