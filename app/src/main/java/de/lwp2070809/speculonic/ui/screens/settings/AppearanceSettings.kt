package de.lwp2070809.speculonic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.ui.components.TopBarState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(viewModel: SettingsViewModel, topBarState: TopBarState) {
    val uiState by viewModel.uiState.collectAsState()
    val title = stringResource(R.string.appearance)

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
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(SettingsConstants.PAGE_PADDING).verticalScroll(rememberScrollState())) {
        
        var expandedTheme by remember { mutableStateOf(false) }
        val themes = listOf(
            ThemeMode.LIGHT to stringResource(R.string.light),
            ThemeMode.DARK to stringResource(R.string.dark),
            ThemeMode.SYSTEM to stringResource(R.string.system)
        )

        ExposedDropdownMenuBox(
            expanded = expandedTheme,
            onExpandedChange = { expandedTheme = !expandedTheme },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = themes.find { it.first == uiState.themeMode }?.second ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.theme_mode)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTheme) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedTheme,
                onDismissRequest = { expandedTheme = false }
            ) {
                themes.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.updateThemeMode(mode)
                            expandedTheme = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))

        
        var expandedColor by remember { mutableStateOf(false) }
        val colors = listOf(
            ColorMode.ALBUM_COVER to stringResource(R.string.album_cover),
            ColorMode.SYSTEM_COLOR to stringResource(R.string.system_color)
        )

        ExposedDropdownMenuBox(
            expanded = expandedColor,
            onExpandedChange = { expandedColor = !expandedColor },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = colors.find { it.first == uiState.colorMode }?.second ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.color_mode)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedColor) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedColor,
                onDismissRequest = { expandedColor = false }
            ) {
                colors.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.updateColorMode(mode)
                            expandedColor = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))

        
        var expandedLanguage by remember { mutableStateOf(false) }
        val languages = listOf(
            "system" to stringResource(R.string.system_default),
            "en" to stringResource(R.string.english),
            "zh" to stringResource(R.string.chinese)
        )

        ExposedDropdownMenuBox(
            expanded = expandedLanguage,
            onExpandedChange = { expandedLanguage = !expandedLanguage },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = uiState.language,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.language)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLanguage) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedLanguage,
                onDismissRequest = { expandedLanguage = false }
            ) {
                languages.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.setLanguage(code)
                            expandedLanguage = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.SPACER_HEIGHT_LARGE))

        var expandedBg by remember { mutableStateOf(false) }
        val bgModes = listOf(
            PlayerBackgroundMode.GAUSSIAN_BLUR to stringResource(R.string.player_background_mode_gaussian_blur),
            PlayerBackgroundMode.GLOW_GRADIENT to stringResource(R.string.player_background_mode_glow_gradient)
        )

        ExposedDropdownMenuBox(
            expanded = expandedBg,
            onExpandedChange = { expandedBg = !expandedBg },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = bgModes.find { it.first == uiState.playerBackgroundMode }?.second ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.player_background_mode)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedBg) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(
                expanded = expandedBg,
                onDismissRequest = { expandedBg = false }
            ) {
                bgModes.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.updatePlayerBackgroundMode(mode)
                            expandedBg = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}


