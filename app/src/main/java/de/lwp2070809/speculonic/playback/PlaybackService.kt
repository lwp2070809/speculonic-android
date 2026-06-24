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
    
    private var silentCacheJob: Job? = null
    private var currentQueueTitle: String? = null

    private var isMetered = false
    private var mobilePlayAllowed = true

    private lateinit var persistence: PlaybackStatePersistence
    private lateinit var errorHandler: PlaybackErrorHandler
    private lateinit var carAudioManager: BluetoothCarManager
    private lateinit var audioFocusHelper: PlaybackAudioFocusHelper

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaSession?.player?.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
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
        mediaSession = MediaSession.Builder(this, placeholderPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(CustomCallback())
            .setSessionExtras(android.os.Bundle().apply { putString("queueTitle", null) })
            .setBitmapLoader(CoilBitmapLoader(this, serviceScope, networkMonitor))
            .build()
        
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        audioFocusHelper = PlaybackAudioFocusHelper(this) { mediaSession?.player }

        persistence = PlaybackStatePersistence(
            serviceScope = serviceScope,
            preferencesManagerProvider = { preferencesManager },
            dbProvider = { db },
            currentQueueTitleProvider = { currentQueueTitle },
            onQueueTitleRestored = {
                currentQueueTitle = it
                mediaSession?.let { session ->
                    session.sessionExtras = android.os.Bundle().apply {
                        putString("queueTitle", it)
                    }
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
            mediaSessionProvider = { mediaSession },
            repositoryProvider = { repository }
        )

        val serviceRef = java.lang.ref.WeakReference(this)
        CacheManager.onRequireCacheRelease = {
            withContext(Dispatchers.Main) {
                val service = serviceRef.get() ?: return@withContext
                LogManager.w("PlaybackService: Cache release requested. Pausing playback and releasing internal player.")
                service.persistence.stopPositionPersistence()
                service.carAudioManager.stopBluetoothLyricsUpdate()
                service.silentCacheJob?.cancel()
                
                val oldPlayer = service.mediaSession?.player
                val newPlaceholderPlayer = ExoPlayer.Builder(service).build()
                service.mediaSession?.player = newPlaceholderPlayer
                oldPlayer?.release()
                
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
                    val dCache = de.lwp2070809.speculonic.data.CacheManager.getDownloadCache(this@PlaybackService, config.maxCacheSize)
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
                    onTriggerSilentCache = { item -> triggerSilentCacheWithDelay(item) }
                ))

                persistence.restorePlaybackState(realPlayer, repository)
                    
                withContext(Dispatchers.Main) {
                    val oldPlayer = mediaSession?.player
                    mediaSession?.player = sessionPlayer
                    
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
            player.release()
            release()
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

    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(androidx.media3.session.SessionCommand("SET_QUEUE_TITLE", android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ) {
            super.onPostConnect(session, controller)
            
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
            if (customCommand.customAction == "SET_QUEUE_TITLE") {
                val title = args.getString("queueTitle")
                currentQueueTitle = title
                session.sessionExtras = android.os.Bundle().apply {
                    putString("queueTitle", title)
                }
                persistence.savePlaybackState(session.player, immediate = true)
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                )
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_UNKNOWN)
            )
        }
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
        unregisterReceiver(noisyReceiver)

        carAudioManager.release()
        mediaSession?.player?.let { persistence.savePlaybackState(it, immediate = true) }
        CacheManager.onRequireCacheRelease = null
        CacheManager.onCacheRebuild = null
        serviceScope.cancel()
        releaseSessionAndPlayer()
        super.onDestroy()
    }
}
