package de.lwp2070809.speculonic

import de.lwp2070809.speculonic.R

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.data.CacheSyncWorker
import de.lwp2070809.speculonic.data.ColorMode
import de.lwp2070809.speculonic.data.DownloadTracker
import de.lwp2070809.speculonic.data.MetadataSyncWorker
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.playback.PlaybackService
import de.lwp2070809.speculonic.ui.MainScreen
import de.lwp2070809.speculonic.ui.theme.SpeculonicTheme
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.PaletteUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var repository: SubsonicRepository

    @Inject
    lateinit var updateManager: de.lwp2070809.speculonic.data.UpdateManager

    @Inject
    lateinit var networkMonitor: de.lwp2070809.speculonic.util.NetworkMonitor

    private var showNowPlayingTrigger by mutableStateOf(false)
    private var showCancelDownloadsDialogTrigger by mutableStateOf(false)

    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val playbackController = remember { PlaybackController.getInstance(context) }
            val playbackStateState = playbackController.playbackState.collectAsState()
            val isPlayingState by remember { derivedStateOf { playbackStateState.value.isPlaying } }
            val currentSongIdState by remember { derivedStateOf { playbackStateState.value.currentSongId } }
            val artworkUriState by remember { derivedStateOf { playbackStateState.value.artworkUri } }
            val scope = rememberCoroutineScope()
            
            val networkMonitor = remember { this@MainActivity.networkMonitor }
            val networkStatus by networkMonitor.networkStatus.collectAsState(initial = de.lwp2070809.speculonic.util.NetworkStatus(true, false))
            val isOnlineReal = networkStatus.isOnline
            val isMetered = networkStatus.isMetered

            val preferencesManager = remember { PreferencesManager.getInstance(context) }
            val themeMode by preferencesManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val colorMode by preferencesManager.colorMode.collectAsState(initial = ColorMode.ALBUM_COVER)
            val mobilePlayAllowed by preferencesManager.mobilePlayAllowed.collectAsState(initial = true)
            val offlineMode by preferencesManager.offlineModeEnabled.collectAsState(initial = false)
            val autoOffline by preferencesManager.autoOfflineOnMetered.collectAsState(initial = false)

            LaunchedEffect(offlineMode) {
                de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.isManualOffline = offlineMode
            }

            LaunchedEffect(isMetered, autoOffline, offlineMode) {
                if (autoOffline) {
                    if (isMetered && !offlineMode) {
                        preferencesManager.saveOfflineModeEnabled(true)
                    } else if (!isMetered && offlineMode) {
                        preferencesManager.saveOfflineModeEnabled(false)
                    }
                }
            }

            val toggleOfflineMode: () -> Unit = {
                scope.launch {
                    val currentOffline = preferencesManager.offlineModeEnabled.first()
                    val autoOfflineEnabled = preferencesManager.autoOfflineOnMetered.first()
                    if (autoOfflineEnabled) {
                        preferencesManager.saveAutoOfflineOnMetered(false)
                        android.widget.Toast.makeText(
                            context.applicationContext,
                            R.string.auto_offline_disabled_toast,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    preferencesManager.saveOfflineModeEnabled(!currentOffline)
                    val toastMsg = if (!currentOffline) {
                        R.string.offline_mode_enabled_toast
                    } else {
                        R.string.offline_mode_disabled_toast
                    }
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        toastMsg,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            val isEffectivelyOnline by remember(networkMonitor, preferencesManager) {
                combine(
                    networkMonitor.networkStatus,
                    preferencesManager.offlineModeEnabled
                ) { status, offline ->
                    status.isOnline && !offline
                }.distinctUntilChanged()
            }.collectAsState(initial = true)

            val isStreamingAllowed by remember(networkMonitor, preferencesManager) {
                combine(
                    networkMonitor.networkStatus,
                    preferencesManager.mobilePlayAllowed,
                    preferencesManager.offlineModeEnabled
                ) { status, allowed, offline ->
                    status.isOnline && (!status.isMetered || allowed) && !offline
                }.distinctUntilChanged()
            }.collectAsState(initial = true)

            var showCancelDownloadsDialog by remember { mutableStateOf(false) }

            LaunchedEffect(showCancelDownloadsDialogTrigger) {
                if (showCancelDownloadsDialogTrigger) {
                    showCancelDownloadsDialog = true
                    showCancelDownloadsDialogTrigger = false
                }
            }

            var updateResult by remember { mutableStateOf<de.lwp2070809.speculonic.data.UpdateManager.UpdateResult?>(null) }

            LaunchedEffect(Unit) {
                launch {
                    de.lwp2070809.speculonic.di.NetworkModule.ServerReachableManager.networkEventFlow.collect { event ->
                        val showToast = if (event == de.lwp2070809.speculonic.di.NetworkModule.NetworkEvent.ServerOffline) {
                            preferencesManager.showOfflineToast.first()
                        } else {
                            true
                        }
                        if (showToast) {
                            val messageResId = when (event) {
                                de.lwp2070809.speculonic.di.NetworkModule.NetworkEvent.ServerOffline -> R.string.server_offline_toast
                                de.lwp2070809.speculonic.di.NetworkModule.NetworkEvent.NetworkRestricted -> R.string.network_restricted_error
                            }
                            android.widget.Toast.makeText(context.applicationContext, messageResId, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                launch {
                    delay(500)
                    playbackController.ensureController()
                }

                DownloadTracker.init(context)
                
                launch {
                    val result = updateManager.checkForUpdates(manual = false)
                    if (result is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.UpdateAvailable) {
                        updateResult = result
                    }
                }
                
                val wasSyncing = preferencesManager.isSyncing.first()
                if (wasSyncing) {
                    val lastSyncTime = preferencesManager.lastSyncTime.first()
                    if (System.currentTimeMillis() - lastSyncTime > 30 * 60 * 1000) {
                        LogManager.w("MainActivity: Detected stuck isSyncing state (>30m). Resetting to false.")
                        preferencesManager.saveIsSyncing(false)
                        MetadataSyncWorker.runOnce(context, forceRefresh = false)
                    } else {
                        LogManager.w("MainActivity: Detected interrupted sync. Triggering re-sync...")
                        MetadataSyncWorker.runOnce(context, forceRefresh = true)
                    }
                }
                
                preferencesManager.logLevel.collectLatest { level ->
                    LogManager.setMinLevel(level)
                }
            }

            
            var batteryStatus by remember { mutableStateOf(false) }
            
            DisposableEffect(context) {
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                        batteryStatus = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    }
                }
                val intentFilter = android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                    addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
                    addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
                }
                
                
                val batteryStatusIntent = androidx.core.content.ContextCompat.registerReceiver(
                    context,
                    receiver,
                    intentFilter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                
                val initialStatus = batteryStatusIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                batteryStatus = initialStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING || initialStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

                onDispose { context.unregisterReceiver(receiver) }
            }


            
            

            
            
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> playbackController.onAppVisibilityChanged(true)
                        Lifecycle.Event.ON_PAUSE -> playbackController.onAppVisibilityChanged(false)
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
            
            
            val artworkUri = remember { derivedStateOf { artworkUriState } }
            val savedSeedColorLight by preferencesManager.lastSeedColorLight.collectAsState(initial = null)
            val savedSeedColorDark by preferencesManager.lastSeedColorDark.collectAsState(initial = null)
            var activeSeedColor by remember { mutableStateOf<Color?>(null) }
            var hasEvaluatedColor by remember { mutableStateOf(false) }
            
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            
            val seedColor = if (hasEvaluatedColor) {
                activeSeedColor
            } else {
                if (darkTheme) {
                    savedSeedColorDark?.let { Color(it) }
                } else {
                    savedSeedColorLight?.let { Color(it) }
                }
            }

            val currentSurfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.toArgb()

            
            
            LaunchedEffect(artworkUri.value, colorMode, darkTheme) {
                if (colorMode == ColorMode.SYSTEM_COLOR) {
                    activeSeedColor = null
                    hasEvaluatedColor = true
                    scope.launch {
                        preferencesManager.saveLastSeedColor(null, true)
                        preferencesManager.saveLastSeedColor(null, false)
                    }
                    return@LaunchedEffect
                }

                val uri = artworkUri.value
                val isPlaybackReady = currentSongIdState.isNotEmpty()

                if (uri != null) {
                    val color = PaletteUtils.getSeedColorAsync(
                        context = context,
                        uri = uri.toString(),
                        isDark = darkTheme,
                        imageLoader = SingletonImageLoader.get(context),
                        fallbackBackgroundColor = currentSurfaceColor
                    )
                    activeSeedColor = color
                    hasEvaluatedColor = true
                    if (color != null) {
                        scope.launch { preferencesManager.saveLastSeedColor(color.toArgb(), darkTheme) }
                    } else {
                        scope.launch { preferencesManager.saveLastSeedColor(null, darkTheme) }
                    }
                } else {
                    activeSeedColor = null
                    if (isPlaybackReady) {
                        hasEvaluatedColor = true
                        scope.launch { preferencesManager.saveLastSeedColor(null, darkTheme) }
                    }
                }
            }

            SpeculonicTheme(
                darkTheme = darkTheme,
                seedColor = seedColor
            ) {
                if (showCancelDownloadsDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showCancelDownloadsDialog = false },
                        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.confirm_cancel_all_downloads_title)) },
                        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.confirm_cancel_all_downloads_message)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showCancelDownloadsDialog = false
                                context.sendBroadcast(Intent(de.lwp2070809.speculonic.playback.DownloadService.ACTION_CANCEL_ALL_DOWNLOADS).setPackage(context.packageName))
                            }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.confirm))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showCancelDownloadsDialog = false }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cancel))
                            }
                        }
                    )
                }


                updateResult?.let { result ->
                    if (result is de.lwp2070809.speculonic.data.UpdateManager.UpdateResult.UpdateAvailable) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { updateResult = null },
                            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.update_available_title)) },
                            text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.update_available_message, result.version, result.releaseNotes)) },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    updateManager.openBrowser(result.url)
                                    updateResult = null
                                }) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.update_now))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { updateResult = null }) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.update_later))
                                }
                            }
                        )
                    }
                }

                MainScreen(
                    windowSizeClass = windowSizeClass,
                    preferencesManager = preferencesManager,
                    playbackController = playbackController,
                    isOnline = isOnlineReal,
                    isEffectivelyOnline = isEffectivelyOnline,
                    isStreamingAllowed = isStreamingAllowed,
                    repository = repository,
                    initialShowNowPlaying = showNowPlayingTrigger,
                    onNowPlayingShown = { showNowPlayingTrigger = false },
                    offlineMode = offlineMode,
                    onToggleOfflineMode = toggleOfflineMode
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == PlaybackService.ACTION_SHOW_PLAYER) {
            showNowPlayingTrigger = true
        } else if (intent?.action == "de.lwp2070809.speculonic.ACTION_ASK_CANCEL_DOWNLOADS") {
            showCancelDownloadsDialogTrigger = true
        }
    }
}
