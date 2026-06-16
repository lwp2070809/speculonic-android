package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.ui.components.TopBarState


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettings(viewModel: SettingsViewModel, topBarState: TopBarState, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val title = stringResource(R.string.about)
    val screenToken = remember { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        topBarState.update(
            title = title,
            actions = {},
            showSearch = false,
            showBack = true,
            token = screenToken
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            topBarState.clear(screenToken)
        }
    }

    
    val versionName = remember {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        } catch (e: Exception) {
            "1.0"
        } ?: "1.0"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.version)) },
            supportingContent = { Text(versionName) }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        
        if (de.lwp2070809.speculonic.BuildConfig.UPDATE_CHECK_ENABLED) {
            var expandedUpdateCheck by remember { mutableStateOf(false) }
            val updateCheckIntervals = de.lwp2070809.speculonic.data.UpdateCheckInterval.entries

            ExposedDropdownMenuBox(
                expanded = expandedUpdateCheck,
                onExpandedChange = { expandedUpdateCheck = !expandedUpdateCheck },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = when (uiState.updateCheckInterval) {
                        de.lwp2070809.speculonic.data.UpdateCheckInterval.STARTUP -> stringResource(R.string.update_check_startup)
                        de.lwp2070809.speculonic.data.UpdateCheckInterval.DAILY -> stringResource(R.string.update_check_daily)
                        de.lwp2070809.speculonic.data.UpdateCheckInterval.WEEKLY -> stringResource(R.string.update_check_weekly)
                        de.lwp2070809.speculonic.data.UpdateCheckInterval.DISABLED -> stringResource(R.string.update_check_disabled)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.check_for_updates)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUpdateCheck) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expandedUpdateCheck,
                    onDismissRequest = { expandedUpdateCheck = false }
                ) {
                    updateCheckIntervals.forEach { interval ->
                        val textRes = when (interval) {
                            de.lwp2070809.speculonic.data.UpdateCheckInterval.STARTUP -> R.string.update_check_startup
                            de.lwp2070809.speculonic.data.UpdateCheckInterval.DAILY -> R.string.update_check_daily
                            de.lwp2070809.speculonic.data.UpdateCheckInterval.WEEKLY -> R.string.update_check_weekly
                            de.lwp2070809.speculonic.data.UpdateCheckInterval.DISABLED -> R.string.update_check_disabled
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(textRes)) },
                            onClick = {
                                viewModel.updateUpdateCheckInterval(interval)
                                expandedUpdateCheck = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        
        ListItem(
            headlineContent = { Text(stringResource(R.string.license)) },
            supportingContent = { Text("AGPL-3.0") }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        
        ListItem(
            headlineContent = { Text(stringResource(R.string.github_repository)) },
            supportingContent = { Text("https://github.com/lwp2070809/speculonic-android") },
            modifier = Modifier.clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/lwp2070809/speculonic-android".toUri())
                    context.startActivity(intent)
                } catch (e: Exception) {
                    
                }
            }
        )
    }
}
