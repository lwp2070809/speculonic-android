package de.lwp2070809.speculonic

import de.lwp2070809.speculonic.R

import android.content.Intent
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var repository: SubsonicRepository

    @Inject
    lateinit var updateManager: de.lwp2070809.speculonic.data.UpdateManager

    private var showNowPlayingTrigger by mutableStateOf(false)
    private var showCancelDownloadsDialogTrigger by mutableStateOf(false)

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
            val isPlayingState by remember(playbackController) {
                playbackController.playbackState.map { it.isPlaying }.distinctUntilChanged()
            }.collectAsState(initial = playbackController.playbackState.value.isPlaying)
            val currentSongIdState by remember(playbackController) {
                playbackController.playbackState.map { it.currentSongId }.distinctUntilChanged()
            }.collectAsState(initial = playbackController.playbackState.value.currentSongId)
            val artworkUriState by remember(playbackController) {
                playbackController.playbackState.map { it.artworkUri }.distinctUntilChanged()
            }.collectAsState(initial = playbackController.playbackState.value.artworkUri)
            val scope = rememberCoroutineScope()
            
            val networkMonitor = remember { de.lwp2070809.speculonic.util.ConnectivityManagerNetworkMonitor(context) }
            val networkStatus by networkMonitor.networkStatus.collectAsState(initial = de.lwp2070809.speculonic.util.NetworkStatus(true, false))
            val isOnlineReal = networkStatus.isOnline
            val isMetered = networkStatus.isMetered

            val preferencesManager = remember { PreferencesManager.getInstance(context) }
            val themeMode by preferencesManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val colorMode by preferencesManager.colorMode.collectAsState(initial = ColorMode.ALBUM_COVER)
            val mobilePlayAllowed by preferencesManager.mobilePlayAllowed.collectAsState(initial = true)
            val notificationReminderEnabled by preferencesManager.notificationReminderEnabled.collectAsState(initial = true)

            val isEffectivelyOnline by remember(networkMonitor, preferencesManager) {
                combine(
                    networkMonitor.networkStatus,
                    preferencesManager.mobilePlayAllowed
                ) { status, allowed ->
                    status.isOnline && (!status.isMetered || allowed)
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
                    delay(500)
                    playbackController.ensureController()
                }

                DownloadTracker.init(context)
                MetadataSyncWorker.schedule(context)
                
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
                } else {
                    
                    MetadataSyncWorker.runOnce(context, forceRefresh = false)
                }
                
                
                CacheSyncWorker.runOnce(context, forceScan = false, healCovers = false)
                
                
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

            
            LaunchedEffect(batteryStatus, isEffectivelyOnline, isMetered) {
                if (batteryStatus && isEffectivelyOnline && !isMetered) {
                    LogManager.i("MainActivity: Conditions met (Charging + Unmetered). Triggering event-driven quick sync.")
                    MetadataSyncWorker.runOnce(context, forceRefresh = false, isQuickOnly = true) 
                }
            }
            
            
            var showNotificationEducationDialog by remember { mutableStateOf(false) }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) {
                        LogManager.w("MainActivity: Notification permission denied by user.")
                    }
                }

                LaunchedEffect(Unit) {
                    val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(isPlayingState) {
                    if (isPlayingState) {
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (!hasPermission && notificationReminderEnabled) {
                            showNotificationEducationDialog = true
                        }
                    }
                }
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
                        imageLoader = SingletonImageLoader.get(context)
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

                if (showNotificationEducationDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showNotificationEducationDialog = false },
                        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.notification_permission_title)) },
                        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.notification_permission_message)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showNotificationEducationDialog = false
                                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                context.startActivity(intent)
                            }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.go_to_settings))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showNotificationEducationDialog = false
                                scope.launch {
                                    preferencesManager.saveNotificationReminderEnabled(false)
                                }
                            }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.dont_remind_me))
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
                    repository = repository,
                    initialShowNowPlaying = showNowPlayingTrigger,
                    onNowPlayingShown = { showNowPlayingTrigger = false }
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
