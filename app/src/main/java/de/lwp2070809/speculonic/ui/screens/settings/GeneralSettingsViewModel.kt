package de.lwp2070809.speculonic.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.data.UpdateCheckInterval
import de.lwp2070809.speculonic.data.UpdateManager
import de.lwp2070809.speculonic.util.LogLevel
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LanguageOption(val tag: String) {
    SYSTEM("system"),
    EN("en"),
    ZH("zh")
}

data class GeneralSettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorMode: ColorMode = ColorMode.ALBUM_COVER,
    val language: LanguageOption = LanguageOption.SYSTEM,
    val playerBackgroundMode: PlayerBackgroundMode = PlayerBackgroundMode.GAUSSIAN_BLUR,
    val mobilePlayAllowed: Boolean = true,
    val autoOfflineOnMetered: Boolean = false,
    val showOfflineToast: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    val updateCheckInterval: UpdateCheckInterval = UpdateCheckInterval.DISABLED,
    val isCheckingUpdate: Boolean = false,
    val manualUpdateResult: UpdateManager.UpdateResult.UpdateAvailable? = null
)

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val updateManager: UpdateManager,
    private val downloadManagerHelper: DownloadManagerHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeneralSettingsUiState())
    val uiState: StateFlow<GeneralSettingsUiState> = _uiState.asStateFlow()

    init {
        observeGeneralPreferences()
    }

    private fun observeGeneralPreferences() {
        viewModelScope.launch {
            combine(
                preferencesManager.mobilePlayAllowed,
                preferencesManager.logLevel,
                preferencesManager.themeMode,
                preferencesManager.colorMode
            ) { mobile, log, theme, color ->
                _uiState.update { current ->
                    current.copy(
                        mobilePlayAllowed = mobile,
                        logLevel = log,
                        themeMode = theme,
                        colorMode = color
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            combine(
                preferencesManager.showOfflineToast,
                preferencesManager.updateCheckInterval,
                preferencesManager.autoOfflineOnMetered,
                preferencesManager.playerBackgroundMode
            ) { showToast, interval, autoOffline, bgMode ->
                _uiState.update { current ->
                    current.copy(
                        showOfflineToast = showToast,
                        updateCheckInterval = interval,
                        autoOfflineOnMetered = autoOffline,
                        playerBackgroundMode = bgMode,
                        language = getCurrentLanguageOption()
                    )
                }
            }.collect()
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesManager.saveThemeMode(mode) }
    }

    fun updateColorMode(mode: ColorMode) {
        viewModelScope.launch { preferencesManager.saveColorMode(mode) }
    }

    fun updatePlayerBackgroundMode(mode: PlayerBackgroundMode) {
        viewModelScope.launch { preferencesManager.savePlayerBackgroundMode(mode) }
    }

    fun setLanguage(option: LanguageOption) {
        val appLocale = if (option == LanguageOption.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(option.tag)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
        _uiState.update { it.copy(language = option) }
    }

    private fun getCurrentLanguageOption(): LanguageOption {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) return LanguageOption.SYSTEM
        val tag = currentLocales.get(0)?.toLanguageTag().orEmpty()
        return when {
            tag.startsWith("en") -> LanguageOption.EN
            tag.startsWith("zh") -> LanguageOption.ZH
            else -> LanguageOption.SYSTEM
        }
    }

    fun updateMobilePlayAllowed(allowed: Boolean) {
        viewModelScope.launch {
            preferencesManager.saveMobilePlayAllowed(allowed)
            downloadManagerHelper.updateRequirements(allowed)
        }
    }

    fun updateAutoOfflineOnMetered(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.saveAutoOfflineOnMetered(enabled) }
    }

    fun updateShowOfflineToast(show: Boolean) {
        viewModelScope.launch { preferencesManager.saveShowOfflineToast(show) }
    }

    fun updateLogLevel(level: LogLevel) {
        viewModelScope.launch {
            preferencesManager.saveLogLevel(level)
            LogManager.setMinLevel(level)
        }
    }

    fun updateUpdateCheckInterval(interval: UpdateCheckInterval) {
        viewModelScope.launch { preferencesManager.saveUpdateCheckInterval(interval) }
    }

    fun checkForUpdatesManually() {
        if (_uiState.value.isCheckingUpdate) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            try {
                when (val result = updateManager.checkForUpdates(manual = true)) {
                    is UpdateManager.UpdateResult.UpdateAvailable -> {
                        _uiState.update { it.copy(manualUpdateResult = result) }
                    }
                    is UpdateManager.UpdateResult.NoUpdate -> {
                        Toast.makeText(context, R.string.update_already_latest, Toast.LENGTH_SHORT).show()
                    }
                    is UpdateManager.UpdateResult.NotConfigured -> {
                        Toast.makeText(context, R.string.update_not_configured, Toast.LENGTH_SHORT).show()
                    }
                    is UpdateManager.UpdateResult.Error -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.update_check_failed, result.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }

    fun dismissManualUpdateDialog() {
        _uiState.update { it.copy(manualUpdateResult = null) }
    }

    fun openBrowser(url: String) {
        updateManager.openBrowser(url)
    }
}
