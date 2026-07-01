package de.lwp2070809.speculonic.ui.screens.settings

import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.domain.model.InconsistentItem
import de.lwp2070809.speculonic.util.LogLevel

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val cacheLocation: String = "",
    val internalCacheSize: String = "0 B",
    val externalCacheSize: String = "0 B",
    val maxCoverCacheSize: Long = 512L * 1024 * 1024,
    val isCoverOverQuota: Boolean = false,
    val mobilePlayAllowed: Boolean = true,
    val showOfflineToast: Boolean = true,
    val backgroundSyncEnabled: Boolean = true,
    val logLevel: LogLevel = LogLevel.INFO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorMode: ColorMode = ColorMode.ALBUM_COVER,
    val silentCacheEnabled: Boolean = true,
    
    val carBluetoothEnabled: Boolean = false,
    val syncPlaybackState: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val duckOnTransientFocusLoss: Boolean = true,
    val pauseOnAudioFocusLoss: Boolean = true,

    val bluetoothLyricsEnabled: Boolean = false,
    val bluetoothLyricsHideProgressBar: Boolean = false,
    val bluetoothCarDeviceNames: Set<String> = emptySet(),
    
    val isSaving: Boolean = false,
    val isRefreshing: Boolean = false,
    val isScanning: Boolean = false,
    val isSyncing: Boolean = false,
    val isPlaying: Boolean = false,
    val syncProgress: String? = null,
    val syncError: String? = null,
    val syncPercentage: Int? = null,
    val showFirstSyncConfirm: Boolean = false,
    val showSafetyGuardConfirm: Boolean = false,
    val safetyGuardMessage: String? = null,

    val isInteractiveScanning: Boolean = false,
    val interactiveScanProgress: Int = 0,
    val interactiveScanStatus: String = "",
    val showInconsistencyDialog: Boolean = false,
    val inconsistentItems: List<InconsistentItem> = emptyList(),

    val showSilentCacheConfirm: Boolean = false,
    val showBluetoothPermissionRequest: Boolean = false,

    val language: String = "System",
    val isTestingConnection: Boolean = false,
    val testConnectionResult: Pair<Boolean, String?>? = null,
    val urlError: String? = null,
    val showMobileSyncConfirm: Boolean = false,
    val showClearCacheConfirm: Boolean = false,
    val showForceSyncConfirm: Boolean = false,
    val trustAllCertificates: Boolean = false,
    val playerBackgroundMode: de.lwp2070809.speculonic.data.PlayerBackgroundMode = de.lwp2070809.speculonic.data.PlayerBackgroundMode.GAUSSIAN_BLUR,
    val updateCheckInterval: de.lwp2070809.speculonic.data.UpdateCheckInterval = de.lwp2070809.speculonic.data.UpdateCheckInterval.DISABLED,
    val autoOfflineOnMetered: Boolean = false,
    val offlineModeEnabled: Boolean = false,
    val artistsCount: Int = 0,
    val albumsCount: Int = 0,
    val songsCount: Int = 0,
    val playlistsCount: Int = 0,
    val lastSyncTime: Long = 0L,
    val syncCoverArtOnForce: Boolean = false,
    val internalCacheBytes: Long = 0L,
    val externalCacheBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val totalSpaceBytes: Long = 0L,
    val cachedSongsCount: Int = 0,
    val playbackCacheBytes: Long = 0L,
    val coverArtCacheBytes: Long = 0L,
    val songCacheBytes: Long = 0L,
    val otherCacheBytes: Long = 0L,
    val playbackCacheSize: String = "0 B",
    val coverArtCacheSize: String = "0 B",
    val songCacheSize: String = "0 B",
    val otherCacheSize: String = "0 B",
    val serverCapabilities: de.lwp2070809.speculonic.domain.repository.ServerCapabilities? = null
)

internal data class PrefsGroup1(
    val serverUrl: String, val username: String, val password: String,
    val cacheLocation: String, val maxCoverCacheSize: Long,
    val syncCoverArtOnForce: Boolean, val lastSyncTime: Long,
    val serverCapabilities: de.lwp2070809.speculonic.domain.repository.ServerCapabilities?
)

internal data class StatsGroup(
    val artists: Int, val albums: Int, val songs: Int, val playlists: Int
)

internal data class PrefsGroup2(
    val mobilePlayAllowed: Boolean, val backgroundSyncEnabled: Boolean,
    val logLevel: LogLevel, val themeMode: ThemeMode, val colorMode: ColorMode
)
internal data class BluetoothPrefs(
    val carEnabled: Boolean, val syncState: Boolean,
    val lyricsEnabled: Boolean, val hideProgress: Boolean, val deviceNames: Set<String>
)
internal data class PlaybackPrefs(
    val skipSilence: Boolean,
    val duckOnTransientFocusLoss: Boolean,
    val pauseOnAudioFocusLoss: Boolean
)
internal data class PrefsGroup3(
    val silentCacheEnabled: Boolean,
    val backgroundMode: de.lwp2070809.speculonic.data.PlayerBackgroundMode,
    val bluetooth: BluetoothPrefs,
    val playback: PlaybackPrefs
)

internal data class PrefsGroup4(
    val trustAllCertificates: Boolean,
    val showOfflineToast: Boolean,
    val updateCheckInterval: de.lwp2070809.speculonic.data.UpdateCheckInterval,
    val autoOfflineOnMetered: Boolean,
    val offlineModeEnabled: Boolean,
    val third: PrefsGroup3
)
