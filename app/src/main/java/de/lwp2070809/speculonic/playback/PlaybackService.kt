package de.lwp2070809.speculonic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import de.lwp2070809.speculonic.MainActivity
import de.lwp2070809.speculonic.data.CacheManager
import de.lwp2070809.speculonic.data.CacheStrategyManager
import de.lwp2070809.speculonic.data.DownloadManagerHelper
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.dataStore
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.util.CoilBitmapLoader
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_SHOW_PLAYER = "de.lwp2070809.speculonic.ACTION_SHOW_PLAYER"
        const val EXTRA_KEY_SESSION_READY = "is_session_ready"

        @Volatile
        var isServiceActive: Boolean = false
            private set

        @Volatile
        var isPlayerPlaying: Boolean = false
            internal set
    }

    @Inject
    lateinit var repository: SubsonicRepository

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var networkMonitor: de.lwp2070809.speculonic.util.NetworkMonitor

    private var mediaSession: MediaSession? = null
    private var cacheStrategyManager: CacheStrategyManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    
    @Volatile private var lastInitializedServerUrl: String? = null
    @Volatile private var lastInitializedUsername: String? = null
    @Volatile private var pendingConfig: Pair<String, String>? = null

    private var silentCacheJob: Job? = null
    private var currentQueueTitle: String? = null

    private var isMetered = false
    private var mobilePlayAllowed = true
    @Volatile private var pauseOnVolumeZero = false

    private lateinit var persistence: PlaybackStatePersistence
    private lateinit var errorHandler: PlaybackErrorHandler
    private lateinit var carAudioManager: BluetoothCarManager
    private lateinit var audioFocusHelper: PlaybackAudioFocusHelper
    lateinit var sleepTimerManager: PlaybackSleepTimerManager
    val volumeCoordinator = VolumeCoordinator()

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaSession?.player?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        isPlayerPlaying = false
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { action = ACTION_SHOW_PLAYER },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val emptyRenderersFactory = androidx.media3.exoplayer.RenderersFactory { eventHandler, _, _, _, metadataRendererOutput ->
            arrayOf(
                androidx.media3.exoplayer.metadata.MetadataRenderer(metadataRendererOutput, eventHandler.looper)
            )
        }
        val placeholderPlayer = ExoPlayer.Builder(this, emptyRenderersFactory).build()
        volumeCoordinator.setPlayer(placeholderPlayer)

        mediaSession = MediaSession.Builder(this, placeholderPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(CustomCallback())
            .setSessionExtras(android.os.Bundle().apply {
                putString("queueTitle", null)
                putBoolean(EXTRA_KEY_SESSION_READY, false)
            })
            .setBitmapLoader(CoilBitmapLoader(this, serviceScope, networkMonitor))
            .build()

        sleepTimerManager = PlaybackSleepTimerManager(serviceScope) { mediaSession }
        
        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            RECEIVER_NOT_EXPORTED
        )

        audioFocusHelper = PlaybackAudioFocusHelper(this, volumeCoordinator) { mediaSession?.player }

        persistence = PlaybackStatePersistence(
            serviceScope = serviceScope,
            preferencesManagerProvider = { preferencesManager },
            dbProvider = { db },
            currentQueueTitleProvider = { currentQueueTitle },
            onQueueTitleRestored = {
                currentQueueTitle = it
                mediaSession?.let { session ->
                    val extras = android.os.Bundle(session.sessionExtras).apply {
                        putString("queueTitle", it)
                    }
                    session.sessionExtras = extras
                }
            }
        )

        errorHandler = PlaybackErrorHandler(
            context = this,
            serviceScope = serviceScope,
            isMetered = { isMetered },
            mobilePlayAllowed = { mobilePlayAllowed },
            dbProvider = { db }
        )

        carAudioManager = BluetoothCarManager(
            context = this,
            serviceScope = serviceScope,
            volumeCoordinator = volumeCoordinator,
            mediaSessionProvider = { mediaSession },
            repositoryProvider = { repository }
        )

        val serviceRef = java.lang.ref.WeakReference(this)
        CacheManager.onRequireCacheRelease = {
            withContext(Dispatchers.Main) {
                val service = serviceRef.get() ?: return@withContext
                LogManager.w("PlaybackService: Cache release requested. Pausing playback and releasing internal player.")
                isPlayerPlaying = false
                service.persistence.stopPositionPersistence()
                service.carAudioManager.stopBluetoothLyricsUpdate()
                service.silentCacheJob?.cancel()
                
                val oldPlayer = service.mediaSession?.player
                val newPlaceholderPlayer = ExoPlayer.Builder(service).build()
                service.mediaSession?.player = newPlaceholderPlayer
                service.volumeCoordinator.setPlayer(newPlaceholderPlayer)
                service.mediaSession?.let { session ->
                    val extras = android.os.Bundle(session.sessionExtras).apply {
                        putBoolean(EXTRA_KEY_SESSION_READY, false)
                    }
                    session.sessionExtras = extras
                }
                
                try {
                    oldPlayer?.stop()
                    oldPlayer?.clearMediaItems()
                    if (oldPlayer is BluetoothCarManager.CarDisguisePlayer) {
                        oldPlayer.wrappedPlayer.release()
                    } else {
                        oldPlayer?.release()
                    }
                } catch (e: Exception) {
                    LogManager.w("PlaybackService: Error releasing old player during cache release", e)
                }
                
                service.cacheStrategyManager = null
                service.isInitializing.set(false)
            }
        }

        CacheManager.onCacheRebuild = {
            serviceRef.get()?.let { service ->
                service.serviceScope.launch(Dispatchers.Main) {
                    LogManager.i("PlaybackService: Rebuilding player after cache clearance.")
                    service.initializeSessionAndPlayer()
                }
            }
        }
        
        initializeSessionAndPlayer()
    }

    private fun initializeSessionAndPlayer() {
        if (!isInitializing.compareAndSet(false, true)) return

        serviceScope.launch {
            try {
                launch(Dispatchers.IO) {
                    DownloadManagerHelper.initializeAsync(this@PlaybackService)
                }
                val prefs = preferencesManager
                
                observeNetworkAndPreferences(prefs)

                val config = withContext(Dispatchers.IO) {
                    val sUrl = prefs.serverUrl.first()
                    val uName = prefs.username.first()
                    val pWord = prefs.password.first()
                    val duckFocus = prefs.duckOnTransientFocusLoss.first()
                    val pauseFocus = prefs.pauseOnAudioFocusLoss.first()
                    pauseOnVolumeZero = prefs.pauseOnVolumeZero.first()
                    val mCacheSize = prefs.maxCacheSize.first()
                    object {
                        val serverUrl = sUrl
                        val username = uName
                        val password = pWord
                        val maxCacheSize = mCacheSize
                        // 只有当 duckFocus 和 pauseFocus 都开启时，才允许 ExoPlayer 原生接管焦点控制。
                        // 如果用户自定义了只开启其中一项，则交由 custom AudioFocusHelper 处理，因此这里必须是 &&。
                        val handleAudioFocus = duckFocus && pauseFocus
                    }
                }

                audioFocusHelper.duckOnTransientFocusLoss = withContext(Dispatchers.IO) {
                    prefs.duckOnTransientFocusLoss.first()
                }
                audioFocusHelper.pauseOnAudioFocusLoss = withContext(Dispatchers.IO) {
                    prefs.pauseOnAudioFocusLoss.first()
                }
                audioFocusHelper.isDefaultFocusHandling = audioFocusHelper.duckOnTransientFocusLoss && audioFocusHelper.pauseOnAudioFocusLoss

                if (config.serverUrl.isBlank()) {
                    isInitializing.set(false)
                    return@launch
                }
                
                cacheStrategyManager = CacheStrategyManager(this@PlaybackService, repository, prefs)

                val (playbackCache, downloadCache) = withContext(Dispatchers.IO) {
                    val pCache = de.lwp2070809.speculonic.data.CacheManager.getPlaybackCache(this@PlaybackService)
                    val dCache = de.lwp2070809.speculonic.data.CacheManager.getDownloadCache(this@PlaybackService)
                    Pair(pCache, dCache)
                }

                val realPlayer = withContext(Dispatchers.Main) {
                    PlayerBuilder(this@PlaybackService).build(
                        playbackCache = playbackCache,
                        downloadCache = downloadCache,
                        handleAudioFocus = config.handleAudioFocus,
                        checkRestriction = { isMetered && !mobilePlayAllowed }
                    )
                }
                
                val sessionPlayer = carAudioManager.CarDisguisePlayer(realPlayer)
                
                sessionPlayer.addListener(PlaybackServiceListener(
                    player = sessionPlayer,
                    errorHandler = errorHandler,
                    persistence = persistence,
                    carAudioManager = carAudioManager,
                    audioFocusHelper = audioFocusHelper,
                    onTriggerSilentCache = { item -> triggerSilentCacheWithDelay(item) },
                    onMediaItemTransitionForTimer = { item, reason -> sleepTimerManager.handleMediaItemTransitionForTimer(item, reason) },
                    onPlaybackEndedForTimer = { sleepTimerManager.handlePlaybackEndedForTimer() },
                    isPauseOnVolumeZeroEnabled = { pauseOnVolumeZero },
                    context = this@PlaybackService
                ))

                persistence.restorePlaybackState(realPlayer, repository)
                    
                withContext(Dispatchers.Main) {
                    val oldPlayer = mediaSession?.player
                    mediaSession?.player = sessionPlayer
                    volumeCoordinator.setPlayer(sessionPlayer)
                    mediaSession?.let { session ->
                        val extras = android.os.Bundle(session.sessionExtras).apply {
                            putBoolean(EXTRA_KEY_SESSION_READY, true)
                        }
                        session.sessionExtras = extras
                    }
                    
                    lastInitializedServerUrl = config.serverUrl
                    lastInitializedUsername = config.username

                    if (oldPlayer is BluetoothCarManager.CarDisguisePlayer) {
                         oldPlayer.wrappedPlayer.release()
                    } else {
                         oldPlayer?.release()
                    }
                    
                    if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled && carAudioManager.isCarBluetoothConnected()) {
                        sessionPlayer.currentMediaItem?.let { carAudioManager.loadLyricsForBluetooth(it) }
                    }

                    if (carAudioManager.carBluetoothEnabled && carAudioManager.syncPlaybackState && carAudioManager.isCarBluetoothConnected()) {
                        carAudioManager.syncPlaybackStateToBluetooth()
                    }
                }
            } catch (e: Exception) {
                LogManager.e("PlaybackService failed to initialize", e)
            } finally {
                isInitializing.set(false)
                pendingConfig?.let { (url, username) ->
                    pendingConfig = null
                    if (url.isNotBlank() && username.isNotBlank() && 
                        (url != lastInitializedServerUrl || username != lastInitializedUsername)) {
                        LogManager.i("PlaybackService: Processing pending config change from initialization window...")
                        serviceScope.launch(Dispatchers.Main) {
                            initializeSessionAndPlayer()
                        }
                    }
                }
            }
        }
    }

    private var prefsObservationJob: Job? = null

    private fun observeNetworkAndPreferences(prefs: PreferencesManager) {
        prefsObservationJob?.cancel()
        prefsObservationJob = serviceScope.launch {
            val networkMonitor = this@PlaybackService.networkMonitor
            launch { networkMonitor.isMetered.collect { isMetered = it } }
            launch { prefs.mobilePlayAllowed.collect { mobilePlayAllowed = it } }
            launch { prefs.pauseOnVolumeZero.collect { pauseOnVolumeZero = it } }
            
            launch {
                kotlinx.coroutines.flow.combine(
                    prefs.serverUrl,
                    prefs.username
                ) { url, username ->
                    Pair(url, username)
                }.collect { (url, username) ->
                    if (isInitializing.get()) {
                        pendingConfig = Pair(url, username)
                        return@collect
                    }
                    if (url.isNotBlank() && username.isNotBlank() && 
                        (url != lastInitializedServerUrl || username != lastInitializedUsername)) {
                        LogManager.i("PlaybackService: Server config changed from '$lastInitializedServerUrl' to '$url'. Re-initializing player...")
                        withContext(Dispatchers.Main) {
                            initializeSessionAndPlayer()
                        }
                    }
                }
            }
            
            launch {
                prefs.carBluetoothEnabled.collect { isEnabled ->
                    carAudioManager.carBluetoothEnabled = isEnabled
                    if (isEnabled) {
                        carAudioManager.init()
                    } else {
                        carAudioManager.release()
                    }
                    
                    if (!isEnabled || !carAudioManager.bluetoothLyricsEnabled) {
                        carAudioManager.stopBluetoothLyricsUpdate()
                        carAudioManager.updateMediaSessionMetadata(null)
                    } else if (carAudioManager.isCarBluetoothConnected()) {
                        mediaSession?.player?.currentMediaItem?.let { item -> carAudioManager.loadLyricsForBluetooth(item) }
                    }
                }
            }
            
            launch { prefs.syncPlaybackState.collect { carAudioManager.syncPlaybackState = it } }

            
            launch {
                prefs.skipSilenceEnabled.collect { skip ->
                    val player = mediaSession?.player
                    if (player is BluetoothCarManager.CarDisguisePlayer) {
                        val wrapped = player.wrappedPlayer
                        if (wrapped is ExoPlayer) {
                            wrapped.skipSilenceEnabled = skip
                        }
                    } else if (player is ExoPlayer) {
                        player.skipSilenceEnabled = skip
                    }
                }
            }

            launch {
                combine(
                    prefs.duckOnTransientFocusLoss,
                    prefs.pauseOnAudioFocusLoss
                ) { duck, pause ->
                    Pair(duck, pause)
                }
                .collect { (duck, pause) ->
                    audioFocusHelper.duckOnTransientFocusLoss = duck
                    audioFocusHelper.pauseOnAudioFocusLoss = pause
                    audioFocusHelper.isDefaultFocusHandling = duck && pause
                    
                    val player = mediaSession?.player
                    if (player != null) {
                        val realPlayer = if (player is BluetoothCarManager.CarDisguisePlayer) {
                            player.wrappedPlayer
                        } else {
                            player
                        }
                        if (realPlayer is ExoPlayer) {
                            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build()
                            realPlayer.setAudioAttributes(audioAttributes, audioFocusHelper.isDefaultFocusHandling)
                        }
                    }
                    
                    if (audioFocusHelper.isDefaultFocusHandling) {
                        audioFocusHelper.abandonAudioFocus()
                        audioFocusHelper.resetLossState()
                    } else {
                        if (player?.isPlaying == true) {
                            audioFocusHelper.requestAudioFocus()
                        }
                    }
                }
            }
            
            launch {
                prefs.bluetoothLyricsEnabled.collect {
                    if (carAudioManager.bluetoothLyricsEnabled == it) return@collect
                    carAudioManager.bluetoothLyricsEnabled = it
                    if (!it || !carAudioManager.carBluetoothEnabled) {
                        carAudioManager.stopBluetoothLyricsUpdate()
                        carAudioManager.updateMediaSessionMetadata(null)
                    } else if (carAudioManager.isCarBluetoothConnected()) {
                        mediaSession?.player?.currentMediaItem?.let { item -> carAudioManager.loadLyricsForBluetooth(item) }
                    }
                }
            }
            launch {
                prefs.bluetoothLyricsHideProgressBar.collect {
                    carAudioManager.bluetoothLyricsHideProgressBar = it
                    if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                        carAudioManager.startBluetoothLyricsUpdate()
                    }
                }
            }
            launch {
                prefs.bluetoothCarDeviceNames.collect {
                    carAudioManager.bluetoothCarDeviceNames = it
                    if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                        carAudioManager.startBluetoothLyricsUpdate()
                    }
                }
            }
        }
    }

    private fun triggerSilentCacheWithDelay(mediaItem: MediaItem) {
        silentCacheJob?.cancel()
        silentCacheJob = serviceScope.launch {
            delay(5000)
            
            val player = mediaSession?.player
            if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
                return@launch
            }
            
            var checkCount = 0
            while (isActive && checkCount < 5) {
                val bufferedDuration = player.bufferedPosition - player.currentPosition
                val isBufferSufficient = bufferedDuration > 30_000 || player.bufferedPercentage > 90
                
                if (isBufferSufficient) break
                delay(2000)
                checkCount++
            }

            if (!isActive || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
                return@launch
            }

            val manager = cacheStrategyManager ?: return@launch
            val metadata = mediaItem.mediaMetadata
            manager.triggerSilentCache(
                songId = mediaItem.mediaId,
                title = metadata.title?.toString() ?: "Unknown",
                artist = metadata.artist?.toString() ?: "Unknown",
                album = metadata.albumTitle?.toString() ?: "Unknown",
                duration = null,
                coverArt = metadata.artworkUri?.toString()
            )
        }
    }

    private fun releaseSessionAndPlayer() {
        persistence.cancelAll()
        carAudioManager.stopBluetoothLyricsUpdate()
        silentCacheJob?.cancel()
        if (!audioFocusHelper.isDefaultFocusHandling) {
            audioFocusHelper.abandonAudioFocus()
        }
        mediaSession?.run {
            release()
            player.release()
        }
        mediaSession = null
        fallbackSession?.run {
            release()
            player.release()
        }
        fallbackSession = null
        cacheStrategyManager = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == "de.lwp2070809.speculonic.action.REFRESH_SAF_PERMISSION") {
                val uri = intent.data
                if (uri != null) {
                    LogManager.i("PlaybackService: Received SAF permission refresh intent for $uri. Binder context permission cache synchronized.")
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var fallbackSession: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession ?: fallbackSession ?: run {
            val emptyRenderersFactory = androidx.media3.exoplayer.RenderersFactory { eventHandler, _, _, _, metadataRendererOutput ->
                arrayOf(
                    androidx.media3.exoplayer.metadata.MetadataRenderer(metadataRendererOutput, eventHandler.looper)
                )
            }
            val placeholderPlayer = ExoPlayer.Builder(this, emptyRenderersFactory).build()
            val newSession = MediaSession.Builder(this, placeholderPlayer).build()
            fallbackSession = newSession
            newSession
        }
    }

    val sleepTimerMode: String get() = sleepTimerManager.sleepTimerMode

    fun setSleepTimer(mode: String, minutes: Int = 0, songCount: Int = 0) {
        sleepTimerManager.setSleepTimer(mode, minutes, songCount)
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancelSleepTimer()
    }

    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val isInternalClient = controller.uid == android.os.Process.myUid()

            val availableSessionCommands = if (isInternalClient) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY))
                    .add(androidx.media3.session.SessionCommand("SET_SLEEP_TIMER", android.os.Bundle.EMPTY))
                    .add(androidx.media3.session.SessionCommand("CANCEL_SLEEP_TIMER", android.os.Bundle.EMPTY))
                    .build()
            } else {
                // 外部系统调用方仅允许标准会话指令，屏蔽应用专有自定义指令
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            }

            val isNotificationOrExternal =
                session.isMediaNotificationController(controller) || !isInternalClient
            val availablePlayerCommands = if (isNotificationOrExternal) {
                // 为平台级 MediaSessionLegacyStub（由 MediaNotificationController 驱动配置）及外部控制器
                // 移除 COMMAND_GET_TIMELINE，使 sessionCompat.setQueue(null) 禁用 Legacy 队列同步，
                // 规避 Android 17 蓝牙栈 MediaPlayerWrapper.isMetadataSynced() 因比对未伪装的 QueueItem
                // 与伪装后的车载歌词 MediaMetadata 不一致而触发 2000ms 超时防抖造成的延迟与吞歌词问题。
                @Suppress("DEPRECATION")
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_GET_TIMELINE)
                    .remove(Player.COMMAND_GET_DEVICE_VOLUME)
                    .remove(Player.COMMAND_SET_DEVICE_VOLUME)
                    .remove(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                    .remove(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                    .remove(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                    .build()
            } else {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            }

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(availableSessionCommands)
                .setAvailablePlayerCommands(availablePlayerCommands)
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            super.onPostConnect(session, controller)
            sleepTimerManager.broadcastSleepTimerState()
            
            if (carAudioManager.carBluetoothEnabled) {
                (session.player as? BluetoothCarManager.CarDisguisePlayer)?.triggerCoverSync()
            }
            
            if (carAudioManager.carBluetoothEnabled && carAudioManager.syncPlaybackState) {
                LogManager.i("PlaybackService: Controller ${controller.packageName} connected. Triggering active playback state sync for bluetooth.")
                carAudioManager.syncPlaybackStateToBluetooth()
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            if (controller.uid != android.os.Process.myUid()) {
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_PERMISSION_DENIED)
                )
            }
            when (customCommand.customAction) {
                "SET_QUEUE_TITLE" -> {
                    val title = args.getString("queueTitle")
                    currentQueueTitle = title
                    val currentExtras = session.sessionExtras
                    session.sessionExtras = android.os.Bundle(currentExtras).apply {
                        putString("queueTitle", title)
                    }
                    persistence.savePlaybackState(session.player, immediate = true)
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
                "SET_SLEEP_TIMER" -> {
                    val mode = args.getString("mode", "OFF")
                    val minutes = args.getInt("minutes", 0)
                    val songCount = args.getInt("songCount", 0)
                    sleepTimerManager.setSleepTimer(mode, minutes, songCount)
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
                "CANCEL_SLEEP_TIMER" -> {
                    sleepTimerManager.cancelSleepTimer()
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_UNKNOWN)
            )
        }
    }

    private var lastNotifiedMediaId: String? = null
    private var lastNotifiedPlaybackState: Int = -1
    private var lastNotifiedPlayWhenReady: Boolean = false

    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ): com.google.common.util.concurrent.ListenableFuture<Void?> {
        val player = session.player
        val currentMediaId = player.currentMediaItem?.mediaId
        val state = player.playbackState
        val playWhenReady = player.playWhenReady

        val isForegroundTransitionRequired = startInForegroundRequired && !isPlaybackOngoing
        val isSongChanged = currentMediaId != lastNotifiedMediaId
        val isStateChanged = state != lastNotifiedPlaybackState || playWhenReady != lastNotifiedPlayWhenReady
        val isLyricsDisguiseActive = (player as? BluetoothCarManager.CarDisguisePlayer)?.isLyricsDisguiseActive == true

        // 仅在车载歌词高频更新帧（且非首次拉起前台、非切歌、非播放状态变化）时拦截系统通知栏刷新，
        // 避免 NotificationManagerService 触发通知限流，让歌词纯粹通过 MediaSessionLegacyStub 直达蓝牙 AVRCP
        if (isLyricsDisguiseActive && !isForegroundTransitionRequired && !isSongChanged && !isStateChanged) {
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        lastNotifiedMediaId = currentMediaId
        lastNotifiedPlaybackState = state
        lastNotifiedPlayWhenReady = playWhenReady
        return super.onUpdateNotificationAsync(session, startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            persistence.savePlaybackState(player, immediate = true)
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        isPlayerPlaying = false
        unregisterReceiver(noisyReceiver)
        sleepTimerManager.release()

        carAudioManager.release()
        mediaSession?.player?.let { persistence.savePlaybackState(it, immediate = true) }
        // 服务销毁时立即重置微件为暂停态，使用应用级非取消作用域避开 serviceScope 取消及 300ms 防抖延迟
        de.lwp2070809.speculonic.SpeculonicApp.applicationScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater.resetPlayingStateImmediate(applicationContext)
        }
        CacheManager.onRequireCacheRelease = null
        CacheManager.onCacheRebuild = null
        serviceScope.cancel()
        releaseSessionAndPlayer()
        super.onDestroy()
    }
}
