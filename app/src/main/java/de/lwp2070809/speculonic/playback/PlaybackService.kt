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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

        private const val SILENT_CACHE_START_DELAY_MS = 5000L
        private const val BUFFER_POLL_INTERVAL_MS = 2000L
        private const val BUFFER_SUFFICIENT_MS = 30_000L
        private const val BUFFER_SUFFICIENT_PERCENT = 90
        private const val MAX_BUFFER_POLL_ATTEMPTS = 5

        @Volatile
        var isServiceActive: Boolean = false
            private set

        @Volatile
        var isPlayerPlaying: Boolean = false
            internal set

        @Volatile
        var isPlaybackIntended: Boolean = false
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

    @Inject
    lateinit var cacheManager: CacheManager

    @Inject
    lateinit var downloadManagerHelper: DownloadManagerHelper

    @Inject
    lateinit var injectedCacheStrategyManager: CacheStrategyManager

    private var mediaSession: MediaSession? = null
    private var cacheStrategyManager: CacheStrategyManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var playerInitJob: Job? = null
    @Volatile private var isServiceDestroyed = false
    
    private val playerBinding = PlaybackSessionBinding()
    private var pendingConfig: Pair<String, String>? = null

    private var silentCacheJob: Job? = null
    private val silentCacheEnabled = MutableStateFlow<Boolean?>(null)
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
        isServiceDestroyed = false
        isServiceActive = true
        isPlayerPlaying = false
        isPlaybackIntended = false

        setupMediaSessionPlaceholder()
        setupPlaybackCoordinators()
        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            RECEIVER_NOT_EXPORTED
        )
        setupCacheLifecycleCallbacks()

        // 偏好设置观测绑定服务全生命周期，防止重载播放器时取消外层协程导致自杀丢监听
        observeNetworkAndPreferences(preferencesManager)
        initializeSessionAndPlayer()
    }

    private fun setupMediaSessionPlaceholder() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
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
            .setSessionExtras(
                android.os.Bundle().apply {
                    putString("queueTitle", null)
                    putBoolean(EXTRA_KEY_SESSION_READY, false)
                }
            )
            .setBitmapLoader(CoilBitmapLoader(this, serviceScope, networkMonitor))
            .build()
    }

    private fun setupPlaybackCoordinators() {
        sleepTimerManager = PlaybackSleepTimerManager(serviceScope) { mediaSession }
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
            cacheManager = cacheManager,
            isMetered = { isMetered },
            mobilePlayAllowed = { mobilePlayAllowed },
            dbProvider = { db }
        )
        carAudioManager = BluetoothCarManager(
            context = this,
            serviceScope = serviceScope,
            mediaSessionProvider = { mediaSession },
            repositoryProvider = { repository }
        )
    }

    private fun setupCacheLifecycleCallbacks() {
        val serviceRef = java.lang.ref.WeakReference(this)
        cacheManager.onRequireCacheRelease = { preservePlayback ->
            withContext(Dispatchers.Main + NonCancellable) {
                val service = serviceRef.get() ?: return@withContext
                service.playerInitJob?.cancelAndJoin()
                service.playerInitJob = null
                if (service.isServiceDestroyed) return@withContext
                service.releasePlayerForCacheReset(preservePlayback)
            }
        }
        cacheManager.onCacheRebuild = {
            serviceRef.get()?.let { service ->
                service.serviceScope.launch(Dispatchers.Main) {
                    LogManager.i("PlaybackService: Rebuilding player after cache clearance.")
                    service.initializeSessionAndPlayer()
                }
            }
        }
    }

    private suspend fun releasePlayerForCacheReset(preservePlayback: Boolean) {
        LogManager.w("PlaybackService: Cache release requested. Pausing playback and releasing internal player.")
        isPlayerPlaying = false
        isPlaybackIntended = false
        de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater.clearServiceTrackOverride()
        persistence.stopPositionPersistence()
        carAudioManager.stopBluetoothLyricsUpdate()
        silentCacheJob?.cancel()

        val oldPlayer = mediaSession?.player
        val detached = playerBinding.detach()
        val newPlaceholderPlayer = ExoPlayer.Builder(this).build()
        mediaSession?.let { session ->
            val extras = android.os.Bundle(session.sessionExtras).apply {
                putBoolean(EXTRA_KEY_SESSION_READY, false)
                if (!preservePlayback) putString("queueTitle", null)
            }
            session.sessionExtras = extras
            session.player = newPlaceholderPlayer
        }
        volumeCoordinator.setPlayer(newPlaceholderPlayer)

        try {
            detached?.pause()
            audioFocusHelper.releaseManualFocus()
            if (!preservePlayback) currentQueueTitle = null
            persistence.finishCacheReset(detached, preservePlayback)
        } finally {
            releaseWrappedOrRawPlayer(oldPlayer)
            cacheStrategyManager = null
            isInitializing.set(false)
        }
    }

    private fun releaseWrappedOrRawPlayer(oldPlayer: Player?) {
        try {
            if (oldPlayer is BluetoothCarManager.CarDisguisePlayer) {
                oldPlayer.wrappedPlayer.release()
            } else {
                oldPlayer?.release()
            }
        } catch (e: Exception) {
            LogManager.w("PlaybackService: Error releasing old player", e)
        }
    }

    private data class PlayerInitConfig(
        val identity: Pair<String, String>,
        val skipSilence: Boolean,
        val duckOnTransientFocusLoss: Boolean,
        val pauseOnAudioFocusLoss: Boolean
    ) {
        // 只有当 duckFocus 和 pauseFocus 都开启时，才允许 ExoPlayer 原生接管焦点控制。
        val handleAudioFocus: Boolean get() = duckOnTransientFocusLoss && pauseOnAudioFocusLoss
    }

    private fun initializeSessionAndPlayer() {
        if (isServiceDestroyed || cacheManager.isReleasing || !isInitializing.compareAndSet(false, true)) return
        pendingConfig = null

        playerInitJob = serviceScope.launch {
            var createdRealPlayer: ExoPlayer? = null
            var attachedToSession = false
            try {
                launch(Dispatchers.IO) { downloadManagerHelper.initializeAsync() }
                val config = loadPlayerInitConfig()
                applyAudioFocusConfig(config)
                if (config.identity.first.isBlank()) {
                    return@launch
                }

                cacheStrategyManager = injectedCacheStrategyManager
                silentCacheEnabled.filterNotNull().first()
                val realPlayer = buildConfiguredExoPlayer(config)?.also { createdRealPlayer = it } ?: return@launch
                val sessionPlayer = carAudioManager.CarDisguisePlayer(realPlayer)
                persistence.restorePlaybackState(realPlayer, repository)
                attachedToSession = attachSessionPlayerOnMain(config, sessionPlayer, realPlayer)
                if (attachedToSession) {
                    createdRealPlayer = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.e("PlaybackService failed to initialize", e)
            } finally {
                cleanupAfterPlayerInit(attachedToSession, createdRealPlayer)
            }
        }
    }

    private suspend fun loadPlayerInitConfig(): PlayerInitConfig = withContext(Dispatchers.IO) {
        val prefs = preferencesManager
        pauseOnVolumeZero = prefs.pauseOnVolumeZero.first()
        prefs.maxCacheSize.first()
        PlayerInitConfig(
            identity = prefs.readServerIdentity(),
            skipSilence = prefs.skipSilenceEnabled.first(),
            duckOnTransientFocusLoss = prefs.duckOnTransientFocusLoss.first(),
            pauseOnAudioFocusLoss = prefs.pauseOnAudioFocusLoss.first()
        )
    }

    private fun applyAudioFocusConfig(config: PlayerInitConfig) {
        audioFocusHelper.duckOnTransientFocusLoss = config.duckOnTransientFocusLoss
        audioFocusHelper.pauseOnAudioFocusLoss = config.pauseOnAudioFocusLoss
        audioFocusHelper.isDefaultFocusHandling = config.handleAudioFocus
    }

    private suspend fun buildConfiguredExoPlayer(config: PlayerInitConfig): ExoPlayer? {
        val (playbackCache, downloadCache) = withContext(Dispatchers.IO) {
            cacheManager.acquirePlaybackCache() to cacheManager.acquireDownloadCache()
        }
        return withContext(Dispatchers.Main) {
            if (isServiceDestroyed) return@withContext null
            PlayerBuilder(this@PlaybackService, db.musicDao()).build(
                playbackCache = playbackCache,
                downloadCache = downloadCache,
                handleAudioFocus = config.handleAudioFocus,
                checkRestriction = { isMetered && !mobilePlayAllowed }
            )
        }
    }

    private fun createServiceListener(sessionPlayer: Player): PlaybackServiceListener =
        PlaybackServiceListener(
            player = sessionPlayer,
            deps = PlaybackServiceDependencies(
                errorHandler = errorHandler,
                persistence = persistence,
                carAudioManager = carAudioManager,
                audioFocusHelper = audioFocusHelper
            ),
            callbacks = PlaybackServiceCallbacks(
                onTriggerSilentCache = { item -> triggerSilentCacheWithDelay(item) },
                onMediaItemTransitionForTimer = { item, reason ->
                    sleepTimerManager.handleMediaItemTransitionForTimer(item, reason)
                },
                onTimelineChangedForTimer = {
                    sleepTimerManager.handleTimelineChangedForTimer(sessionPlayer)
                },
                onPlaybackEndedForTimer = {
                    sleepTimerManager.handlePlaybackEndedForTimer()
                },
                isPauseOnVolumeZeroEnabled = { pauseOnVolumeZero }
            ),
            context = this@PlaybackService
        )

    private suspend fun attachSessionPlayerOnMain(
        config: PlayerInitConfig,
        sessionPlayer: BluetoothCarManager.CarDisguisePlayer,
        realPlayer: ExoPlayer
    ): Boolean = withContext(Dispatchers.Main) {
        val session = mediaSession ?: return@withContext false
        val configIsStale = pendingConfig?.let { it != config.identity } == true
        if (isServiceDestroyed || cacheManager.isReleasing || configIsStale) {
            return@withContext false
        }
        val oldPlayer = session.player
        val listener = createServiceListener(sessionPlayer)
        playerBinding.attach(config.identity, sessionPlayer, realPlayer, listener, config.skipSilence)
        session.player = sessionPlayer
        volumeCoordinator.setPlayer(sessionPlayer)
        session.sessionExtras = android.os.Bundle(session.sessionExtras).apply {
            putBoolean(EXTRA_KEY_SESSION_READY, true)
        }

        releaseWrappedOrRawPlayer(oldPlayer)

        listener.publishWidgetState()
        syncCarBluetoothInitialState(sessionPlayer)
        true
    }

    private fun syncCarBluetoothInitialState(sessionPlayer: Player) {
        if (!carAudioManager.carBluetoothEnabled || !carAudioManager.isCarBluetoothConnected()) return
        if (carAudioManager.bluetoothLyricsEnabled) {
            sessionPlayer.currentMediaItem?.let { carAudioManager.loadLyricsForBluetooth(it) }
        }
        if (carAudioManager.syncPlaybackState) {
            carAudioManager.syncPlaybackStateToBluetooth()
        }
    }

    private suspend fun cleanupAfterPlayerInit(
        attachedToSession: Boolean,
        orphanPlayer: ExoPlayer?
    ) {
        if (!attachedToSession && orphanPlayer != null) {
            withContext(Dispatchers.Main.immediate + kotlinx.coroutines.NonCancellable) {
                runCatching { orphanPlayer.release() }
            }
        }
        isInitializing.set(false)
        if (!isServiceDestroyed && !cacheManager.isReleasing) {
            pendingConfig?.let { identity ->
                pendingConfig = null
                if (playerBinding.needsPlayer(identity)) {
                    LogManager.i("PlaybackService: Processing pending config change from initialization window...")
                    serviceScope.launch(Dispatchers.Main) { initializeSessionAndPlayer() }
                }
            }
        }
    }

    private var prefsObservationJob: Job? = null

    private fun observeNetworkAndPreferences(prefs: PreferencesManager) {
        prefsObservationJob?.cancel()
        prefsObservationJob = serviceScope.launch {
            launch { networkMonitor.isMetered.collect { isMetered = it } }
            launch { prefs.mobilePlayAllowed.collect { mobilePlayAllowed = it } }
            launch { prefs.pauseOnVolumeZero.collect { pauseOnVolumeZero = it } }
            launch {
                prefs.silentCacheEnabled.collect { enabled ->
                    silentCacheEnabled.value = enabled
                    if (!enabled) silentCacheJob?.cancel()
                }
            }
            launch { observeServerConfigChanges(prefs) }
            launch { observeCarBluetoothPreferences(prefs) }
            launch { prefs.syncPlaybackState.collect { carAudioManager.syncPlaybackState = it } }
            launch { observeSkipSilencePreference(prefs) }
            launch { observeAudioFocusPreferences(prefs) }
            launch { observeBluetoothLyricsEnabled(prefs) }
            launch {
                prefs.bluetoothLyricsHideProgressBar.collect {
                    carAudioManager.bluetoothLyricsHideProgressBar = it
                    restartBluetoothLyricsIfActive()
                }
            }
            launch {
                prefs.bluetoothCarDeviceNames.collect {
                    val wasConnected = carAudioManager.isCarBluetoothConnected()
                    carAudioManager.bluetoothCarDeviceNames = it
                    val isNowConnected = carAudioManager.isCarBluetoothConnected()
                    if (carAudioManager.carBluetoothEnabled && !wasConnected && isNowConnected) {
                        (mediaSession?.player as? BluetoothCarManager.CarDisguisePlayer)?.triggerCoverSync()
                        if (carAudioManager.syncPlaybackState) {
                            carAudioManager.syncPlaybackStateToBluetooth()
                        }
                    }
                    syncBluetoothLyricsAvailability()
                }
            }
        }
    }

    private suspend fun observeServerConfigChanges(prefs: PreferencesManager) {
        combine(prefs.serverUrl, prefs.username) { _, _ -> prefs.readServerIdentity() }
            .collect { identity ->
                if (isInitializing.get()) {
                    pendingConfig = identity
                    return@collect
                }
                if (playerBinding.needsPlayer(identity)) {
                    LogManager.i("PlaybackService: Initializing player for the configured server.")
                    initializeSessionAndPlayer()
                }
            }
    }

    private suspend fun observeCarBluetoothPreferences(prefs: PreferencesManager) {
        prefs.carBluetoothEnabled.collect { isEnabled ->
            carAudioManager.carBluetoothEnabled = isEnabled
            if (isEnabled) carAudioManager.init() else carAudioManager.release()
            syncBluetoothLyricsAvailability()
        }
    }

    private suspend fun observeBluetoothLyricsEnabled(prefs: PreferencesManager) {
        prefs.bluetoothLyricsEnabled.collect { enabled ->
            if (carAudioManager.bluetoothLyricsEnabled == enabled) return@collect
            carAudioManager.bluetoothLyricsEnabled = enabled
            syncBluetoothLyricsAvailability()
        }
    }

    private fun syncBluetoothLyricsAvailability() {
        val available = carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled &&
            carAudioManager.isCarBluetoothConnected()
        if (!available) {
            carAudioManager.lyricsManager.release()
            carAudioManager.updateMediaSessionMetadata(null)
        } else {
            mediaSession?.player?.currentMediaItem?.let { carAudioManager.loadLyricsForBluetooth(it) }
        }
    }

    private fun restartBluetoothLyricsIfActive() {
        if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
            carAudioManager.startBluetoothLyricsUpdate()
        }
    }

    private suspend fun observeSkipSilencePreference(prefs: PreferencesManager) {
        prefs.skipSilenceEnabled.collect(playerBinding::setSkipSilence)
    }

    private suspend fun observeAudioFocusPreferences(prefs: PreferencesManager) {
        combine(prefs.duckOnTransientFocusLoss, prefs.pauseOnAudioFocusLoss) { duck, pause -> duck to pause }
            .collect { (duck, pause) ->
                audioFocusHelper.duckOnTransientFocusLoss = duck
                audioFocusHelper.pauseOnAudioFocusLoss = pause
                audioFocusHelper.isDefaultFocusHandling = duck && pause

                val player = mediaSession?.player
                val realPlayer = (player as? BluetoothCarManager.CarDisguisePlayer)?.wrappedPlayer ?: player
                if (realPlayer is ExoPlayer) {
                    val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build()
                    realPlayer.setAudioAttributes(audioAttributes, audioFocusHelper.isDefaultFocusHandling)
                }

                if (audioFocusHelper.isDefaultFocusHandling) {
                    audioFocusHelper.abandonAudioFocus()
                    audioFocusHelper.resetLossState()
                } else if (player?.isPlaying == true) {
                    audioFocusHelper.requestAudioFocus()
                }
            }
    }

    private fun triggerSilentCacheWithDelay(mediaItem: MediaItem) {
        silentCacheJob?.cancel()
        if (silentCacheEnabled.value != true) return
        silentCacheJob = serviceScope.launch {
            delay(SILENT_CACHE_START_DELAY_MS)
            val player = mediaSession?.player ?: return@launch
            if (!isActivelyPlayingItem(player, mediaItem.mediaId)) return@launch

            waitForBufferSufficiency(player)
            if (!isActive || !isActivelyPlayingItem(player, mediaItem.mediaId)) return@launch

            val manager = cacheStrategyManager ?: return@launch
            manager.triggerSilentCache(
                songId = mediaItem.mediaId,
                title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
            )
        }
    }

    private fun isActivelyPlayingItem(player: Player, mediaId: String): Boolean {
        return player.currentMediaItem?.mediaId == mediaId && player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE
    }

    private suspend fun waitForBufferSufficiency(player: Player) {
        var checkCount = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && checkCount < MAX_BUFFER_POLL_ATTEMPTS) {
            val bufferedDuration = player.bufferedPosition - player.currentPosition
            val isBufferSufficient = bufferedDuration > BUFFER_SUFFICIENT_MS ||
                player.bufferedPercentage > BUFFER_SUFFICIENT_PERCENT
            if (isBufferSufficient) return
            delay(BUFFER_POLL_INTERVAL_MS)
            checkCount++
        }
    }

    private fun releaseSessionAndPlayer() {
        persistence.cancelAll()
        carAudioManager.stopBluetoothLyricsUpdate()
        silentCacheJob?.cancel()
        audioFocusHelper.releaseManualFocus()
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
                    LogManager.i(
                        "PlaybackService: Received SAF permission refresh intent for $uri. " +
                            "Binder context permission cache synchronized."
                    )
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

    val sleepTimerMode: SleepTimerMode get() = sleepTimerManager.sleepTimerMode

    fun setSleepTimer(mode: SleepTimerMode, minutes: Int = 0, songCount: Int = 0) {
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
                val builder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_GET_TIMELINE)
                    .remove(Player.COMMAND_GET_DEVICE_VOLUME)
                    .remove(Player.COMMAND_SET_DEVICE_VOLUME)
                    .remove(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                    .remove(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                    .remove(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                // 对所有非本应用外部三方控制器额外剥夺队列修改权限，禁止未授权篡改播放列表
                if (!isInternalClient && !session.isMediaNotificationController(controller)) {
                    builder
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                }
                builder.build()
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
                LogManager.i(
                    "PlaybackService: Controller ${controller.packageName} connected. " +
                        "Triggering active playback state sync for bluetooth."
                )
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
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED)
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
                    playerBinding.player?.let { persistence.savePlaybackState(it, immediate = true) }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
                "SET_SLEEP_TIMER" -> {
                    val mode = SleepTimerMode.fromName(args.getString("mode"))
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
        val isRegularPlaybackUpdate = isForegroundTransitionRequired || isSongChanged || isStateChanged

        // 仅在车载歌词高频更新帧（且非首次拉起前台、非切歌、非播放状态变化）时拦截系统通知栏刷新，
        // 避免 NotificationManagerService 触发通知限流，让歌词纯粹通过 MediaSessionLegacyStub 直达蓝牙 AVRCP
        if (isLyricsDisguiseActive && !isRegularPlaybackUpdate) {
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
            playerBinding.player?.let { persistence.savePlaybackState(it, immediate = true) }
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        isServiceActive = false
        isPlayerPlaying = false
        isPlaybackIntended = false
        de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater.clearServiceTrackOverride()
        unregisterReceiver(noisyReceiver)
        sleepTimerManager.release()

        carAudioManager.release()
        playerBinding.detach()?.let { persistence.savePlaybackState(it, immediate = true) }
        // 服务销毁时立即重置微件为暂停态，使用应用级非取消作用域避开 serviceScope 取消及 300ms 防抖延迟
        de.lwp2070809.speculonic.SpeculonicApp.applicationScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            de.lwp2070809.speculonic.ui.widget.SpeculonicWidgetUpdater.resetPlayingStateImmediate(applicationContext)
        }
        cacheManager.onRequireCacheRelease = null
        cacheManager.onCacheRebuild = null
        serviceScope.cancel()
        releaseSessionAndPlayer()
        super.onDestroy()
    }
}
