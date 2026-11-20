package de.lwp2070809.speculonic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.data.ThemeMode
import de.lwp2070809.speculonic.playback.DownloadController
import de.lwp2070809.speculonic.playback.DownloadService
import de.lwp2070809.speculonic.playback.PlaybackService
import de.lwp2070809.speculonic.ui.MainScreen
import de.lwp2070809.speculonic.ui.MainViewModel
import de.lwp2070809.speculonic.ui.components.UpdateChangelogDialog
import de.lwp2070809.speculonic.ui.theme.SpeculonicTheme
import de.lwp2070809.speculonic.util.CoverArtKeyUtils
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.LogTag
import de.lwp2070809.speculonic.util.NetworkUtils

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    private var showNowPlayingTrigger by mutableStateOf(false)
    private var showCancelDownloadsDialogTrigger by mutableStateOf(false)

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            SpeculonicRootContent()
        }
    }

    @Composable
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun SpeculonicRootContent() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
        val networkStatus by mainViewModel.networkStatus.collectAsStateWithLifecycle()
        val serverUrlState by mainViewModel.serverUrl.collectAsStateWithLifecycle()
        val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
        val colorMode by mainViewModel.colorMode.collectAsStateWithLifecycle()
        val offlineMode by mainViewModel.offlineMode.collectAsStateWithLifecycle()
        val isEffectivelyOnline by mainViewModel.isEffectivelyOnline.collectAsStateWithLifecycle()
        val isStreamingAllowed by mainViewModel.isStreamingAllowed.collectAsStateWithLifecycle()
        val savedSeedColorLight by mainViewModel.savedSeedColorLight.collectAsStateWithLifecycle()
        val savedSeedColorDark by mainViewModel.savedSeedColorDark.collectAsStateWithLifecycle()
        val activeSeedColor by mainViewModel.activeSeedColor.collectAsStateWithLifecycle()
        val hasEvaluatedColor by mainViewModel.hasEvaluatedColor.collectAsStateWithLifecycle()
        val updateResult by mainViewModel.updateAvailableResult.collectAsStateWithLifecycle()

        var permissionRationaleDialog by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
        var permissionSettingsDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
        var showCancelDownloadsDialog by remember { mutableStateOf(false) }

        PermissionLaunchers(
            serverUrlState = serverUrlState.orEmpty(),
            onShowRationale = { title, msg, onRetry -> permissionRationaleDialog = Triple(title, msg, onRetry) },
            onShowSettings = { title, msg -> permissionSettingsDialog = title to msg }
        )

        LaunchedEffect(showCancelDownloadsDialogTrigger) {
            if (showCancelDownloadsDialogTrigger) {
                showCancelDownloadsDialog = true
                showCancelDownloadsDialogTrigger = false
            }
        }

        LaunchedEffect(mainViewModel) {
            mainViewModel.toastMessageRes.collect { resId ->
                Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
            }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mainViewModel.onAppVisibilityChanged(true)
                    Lifecycle.Event.ON_PAUSE -> mainViewModel.onAppVisibilityChanged(false)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val normalizedArtworkUri by remember {
            derivedStateOf {
                val uri = playbackState.artworkUri ?: return@derivedStateOf null
                val uriStr = uri.toString()
                val cleaned = CoverArtKeyUtils.stripVolatileParams(uriStr)
                if (cleaned != uriStr) cleaned.toUri() else uri
            }
        }
        val hasCurrentSong by remember { derivedStateOf { playbackState.currentSongId.isNotEmpty() } }

        val darkTheme = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        val seedColor = if (hasEvaluatedColor) {
            activeSeedColor
        } else {
            val cached = if (darkTheme) savedSeedColorDark else savedSeedColorLight
            cached?.let { Color(it) }
        }

        SpeculonicTheme(darkTheme = darkTheme, seedColor = seedColor) {
            val currentSurfaceColor = MaterialTheme.colorScheme.surface.toArgb()

            LaunchedEffect(normalizedArtworkUri, hasCurrentSong, colorMode, darkTheme) {
                mainViewModel.evaluateSeedColor(
                    artworkUri = normalizedArtworkUri,
                    hasCurrentSong = hasCurrentSong,
                    colorMode = colorMode,
                    darkTheme = darkTheme,
                    fallbackSurfaceColor = currentSurfaceColor,
                    imageLoader = SingletonImageLoader.get(context)
                )
            }

            PermissionAndDownloadDialogs(
                permissionRationaleDialog = permissionRationaleDialog,
                onDismissRationale = { permissionRationaleDialog = null },
                permissionSettingsDialog = permissionSettingsDialog,
                onDismissSettings = { permissionSettingsDialog = null },
                onOpenAppSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                },
                showCancelDownloadsDialog = showCancelDownloadsDialog,
                onDismissCancelDownloads = { showCancelDownloadsDialog = false },
                onConfirmCancelDownloads = {
                    showCancelDownloadsDialog = false
                    context.sendBroadcast(
                        Intent(DownloadService.ACTION_CANCEL_ALL_DOWNLOADS).setPackage(context.packageName)
                    )
                }
            )

            MainScreen(
                mainViewModel = mainViewModel,
                isOnline = networkStatus.isOnline,
                isEffectivelyOnline = isEffectivelyOnline,
                isStreamingAllowed = isStreamingAllowed,
                initialShowNowPlaying = showNowPlayingTrigger,
                onNowPlayingShown = { showNowPlayingTrigger = false },
                offlineMode = offlineMode,
                onToggleOfflineMode = { mainViewModel.toggleOfflineMode() }
            )

            updateResult?.let { result ->
                UpdateChangelogDialog(
                    version = result.version,
                    releaseNotes = result.releaseNotes,
                    onUpdateNow = { mainViewModel.openUpdateBrowser(result.url) },
                    onDismiss = { mainViewModel.dismissUpdateDialog() }
                )
            }
        }
    }

    @Composable
    private fun PermissionLaunchers(
        serverUrlState: String,
        onShowRationale: (String, String, () -> Unit) -> Unit,
        onShowSettings: (String, String) -> Unit
    ) {
        val context = LocalContext.current
        var notificationLauncherRef: ActivityResultLauncher<String>? = null
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (!isGranted) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    onShowRationale(
                        getString(R.string.permission_notification_title),
                        getString(R.string.permission_notification_rationale)
                    ) {
                        notificationLauncherRef?.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    onShowSettings(
                        getString(R.string.permission_notification_title),
                        getString(R.string.permission_notification_settings_guide)
                    )
                }
            }
        }
        notificationLauncherRef = notificationPermissionLauncher

        LaunchedEffect(Unit) {
            DownloadController.permissionRequests.collect {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_MIN_SDK) {
            var localNetworkLauncherRef: ActivityResultLauncher<String>? = null
            val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                LogManager.i(LogTag.APP, "MainActivity: Local network permission granted = $isGranted")
                if (!isGranted) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)) {
                        onShowRationale(
                            getString(R.string.permission_local_network_title),
                            getString(R.string.permission_local_network_rationale)
                        ) {
                            localNetworkLauncherRef?.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }
                    } else {
                        onShowSettings(
                            getString(R.string.permission_local_network_title),
                            getString(R.string.permission_local_network_settings_guide)
                        )
                    }
                }
            }
            localNetworkLauncherRef = localNetworkPermissionLauncher

            LaunchedEffect(serverUrlState) {
                if (serverUrlState.isNotBlank() && NetworkUtils.isLocalNetworkAddress(serverUrlState)) {
                    val granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionAndDownloadDialogs(
        permissionRationaleDialog: Triple<String, String, () -> Unit>?,
        onDismissRationale: () -> Unit,
        permissionSettingsDialog: Pair<String, String>?,
        onDismissSettings: () -> Unit,
        onOpenAppSettings: () -> Unit,
        showCancelDownloadsDialog: Boolean,
        onDismissCancelDownloads: () -> Unit,
        onConfirmCancelDownloads: () -> Unit
    ) {
        permissionRationaleDialog?.let { (title, message, onRetry) ->
            AlertDialog(
                onDismissRequest = onDismissRationale,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        onDismissRationale()
                        onRetry()
                    }) {
                        Text(stringResource(R.string.action_grant))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRationale) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        permissionSettingsDialog?.let { (title, message) ->
            AlertDialog(
                onDismissRequest = onDismissSettings,
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        onDismissSettings()
                        onOpenAppSettings()
                    }) {
                        Text(stringResource(R.string.action_go_to_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissSettings) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showCancelDownloadsDialog) {
            AlertDialog(
                onDismissRequest = onDismissCancelDownloads,
                title = { Text(stringResource(R.string.confirm_cancel_all_downloads_title)) },
                text = { Text(stringResource(R.string.confirm_cancel_all_downloads_message)) },
                confirmButton = {
                    TextButton(onClick = onConfirmCancelDownloads) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissCancelDownloads) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            PlaybackService.ACTION_SHOW_PLAYER -> showNowPlayingTrigger = true
            ACTION_ASK_CANCEL_DOWNLOADS -> showCancelDownloadsDialogTrigger = true
        }
    }

    private companion object {
        const val LOCAL_NETWORK_MIN_SDK = 37
        const val ACTION_ASK_CANCEL_DOWNLOADS = "de.lwp2070809.speculonic.ACTION_ASK_CANCEL_DOWNLOADS"
    }
}
