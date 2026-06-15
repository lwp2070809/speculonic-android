package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.lwp2070809.speculonic.domain.model.InconsistentItem

@Composable
fun InconsistencyDialog(
    inconsistentItems: List<InconsistentItem>,
    onDismissRequest: () -> Unit,
    onResolveItem: (InconsistentItem, String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
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
                    if (inconsistentItems.isEmpty()) {
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
                    } else {
                        Text(
                            text = stringResource(R.string.inconsistency_dialog_found, inconsistentItems.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (inconsistentItems.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(inconsistentItems) { item ->
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
                                    }
                                    Text(text = reason, color = MaterialTheme.colorScheme.error)
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                trailingContent = {
                                    Row {
                                        TextButton(onClick = { onResolveItem(item, "DELETE") }) {
                                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                        }
                                        if (item.type != InconsistentItem.Type.ORPHANED_FILE) {
                                            TextButton(onClick = { onResolveItem(item, "REDOWNLOAD") }) {
                                                Text(stringResource(R.string.redownload))
                                            }
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
