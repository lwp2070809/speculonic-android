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
import android.media.AudioFocusRequest
import android.media.AudioAttributes

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
    private var isInitializing = false
    
    private var silentCacheJob: Job? = null
    private var currentQueueTitle: String? = null

    private var isMetered = false
    private var mobilePlayAllowed = true

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var duckOnTransientFocusLoss = true
    private var pauseOnAudioFocusLoss = true
    private var isDefaultFocusHandling = true
    private var focusRequest: AudioFocusRequest? = null
    private var playWhenReadyBeforeLoss = false
    private var isTransientLossActive = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isDefaultFocusHandling) return@OnAudioFocusChangeListener
        val player = mediaSession?.player ?: return@OnAudioFocusChangeListener
        val realPlayer = if (player is BluetoothCarManager.CarDisguisePlayer) {
            player.wrappedPlayer
        } else {
            player
        }
        if (realPlayer is ExoPlayer) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (pauseOnAudioFocusLoss) {
                        playWhenReadyBeforeLoss = false
                        isTransientLossActive = false
                        realPlayer.pause()
                        abandonAudioFocus()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    playWhenReadyBeforeLoss = realPlayer.playWhenReady
                    isTransientLossActive = true
                    realPlayer.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (duckOnTransientFocusLoss) {
                        realPlayer.volume = 0.2f
                    } else {
                        playWhenReadyBeforeLoss = realPlayer.playWhenReady
                        isTransientLossActive = true
                        realPlayer.pause()
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    realPlayer.volume = 1.0f
                    if (isTransientLossActive) {
                        isTransientLossActive = false
                        if (playWhenReadyBeforeLoss) {
                            realPlayer.play()
                            playWhenReadyBeforeLoss = false
                        }
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(): Int {
        if (focusRequest == null) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }
        return audioManager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
    }

    private lateinit var persistence: PlaybackStatePersistence
    private lateinit var errorHandler: PlaybackErrorHandler
    private lateinit var carAudioManager: BluetoothCarManager

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
        
        val placeholderPlayer = ExoPlayer.Builder(this).build()
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

        persistence = PlaybackStatePersistence(
            serviceScope = serviceScope,
            preferencesManagerProvider = { preferencesManager },
            dbProvider = { db },
            currentQueueTitleProvider = { currentQueueTitle },
            onQueueTitleRestored = { currentQueueTitle = it }
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
        carAudioManager.init()



        CacheManager.onRequireCacheRelease = {
            withContext(Dispatchers.Main) {
                LogManager.w("PlaybackService: Cache release requested. Pausing playback and releasing internal player.")
                persistence.stopPositionPersistence()
                carAudioManager.stopBluetoothLyricsUpdate()
                silentCacheJob?.cancel()
                
                val oldPlayer = mediaSession?.player
                val newPlaceholderPlayer = ExoPlayer.Builder(this@PlaybackService).build()
                mediaSession?.player = newPlaceholderPlayer
                oldPlayer?.release()
                
                cacheStrategyManager = null
                isInitializing = false
            }
        }

        CacheManager.onCacheRebuild = {
            serviceScope.launch(Dispatchers.Main) {
                LogManager.i("PlaybackService: Rebuilding player after cache clearance.")
                initializeSessionAndPlayer()
            }
        }
        
        initializeSessionAndPlayer()
    }

    private fun initializeSessionAndPlayer() {
        if (isInitializing) return
        isInitializing = true

        serviceScope.launch {
            try {
                val prefs = preferencesManager
                
                observeNetworkAndPreferences(prefs)

                val config = withContext(Dispatchers.IO) {
                    val data = this@PlaybackService.dataStore.data.first()
                    val sUrl = prefs.serverUrl.first()
                    val uName = prefs.username.first()
                    val pWord = prefs.password.first()
                    val duckFocus = data[PreferencesManager.DUCK_ON_TRANSIENT_FOCUS_LOSS] ?: true
                    val pauseFocus = data[PreferencesManager.PAUSE_ON_AUDIO_FOCUS_LOSS] ?: true
                    object {
                        val serverUrl = sUrl
                        val username = uName
                        val password = pWord
                        val maxCacheSize = data[PreferencesManager.MAX_CACHE_SIZE] ?: (1024L * 1024 * 1024)
                        val handleAudioFocus = duckFocus && pauseFocus
                    }
                }

                duckOnTransientFocusLoss = withContext(Dispatchers.IO) {
                    this@PlaybackService.dataStore.data.first()[PreferencesManager.DUCK_ON_TRANSIENT_FOCUS_LOSS] ?: true
                }
                pauseOnAudioFocusLoss = withContext(Dispatchers.IO) {
                    this@PlaybackService.dataStore.data.first()[PreferencesManager.PAUSE_ON_AUDIO_FOCUS_LOSS] ?: true
                }
                isDefaultFocusHandling = duckOnTransientFocusLoss && pauseOnAudioFocusLoss

                if (config.serverUrl.isBlank()) {
                    isInitializing = false
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
                
                setupPlayerListeners(sessionPlayer)
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
                isInitializing = false
            }
        }
    }

    private fun observeNetworkAndPreferences(prefs: PreferencesManager) {
        val networkMonitor = this@PlaybackService.networkMonitor
        serviceScope.launch { networkMonitor.isMetered.collect { isMetered = it } }
        serviceScope.launch { prefs.mobilePlayAllowed.collect { mobilePlayAllowed = it } }
        
        serviceScope.launch {
            prefs.carBluetoothEnabled.collect {
                carAudioManager.carBluetoothEnabled = it
                if (!it || !carAudioManager.bluetoothLyricsEnabled) {
                    carAudioManager.stopBluetoothLyricsUpdate()
                    carAudioManager.updateMediaSessionMetadata(null)
                } else if (carAudioManager.isCarBluetoothConnected()) {
                    mediaSession?.player?.currentMediaItem?.let { item -> carAudioManager.loadLyricsForBluetooth(item) }
                }
            }
        }
        
        serviceScope.launch { prefs.syncPlaybackState.collect { carAudioManager.syncPlaybackState = it } }
        
        serviceScope.launch {
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

        serviceScope.launch {
            combine(
                prefs.duckOnTransientFocusLoss,
                prefs.pauseOnAudioFocusLoss
            ) { duck, pause ->
                Pair(duck, pause)
            }
            .collect { (duck, pause) ->
                duckOnTransientFocusLoss = duck
                pauseOnAudioFocusLoss = pause
                isDefaultFocusHandling = duck && pause
                
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
                        realPlayer.setAudioAttributes(audioAttributes, isDefaultFocusHandling)
                    }
                }
                
                if (isDefaultFocusHandling) {
                    abandonAudioFocus()
                    isTransientLossActive = false
                    playWhenReadyBeforeLoss = false
                }
            }
        }
        
        serviceScope.launch {
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
        serviceScope.launch {
            prefs.bluetoothLyricsHideProgressBar.collect {
                carAudioManager.bluetoothLyricsHideProgressBar = it
                if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                    carAudioManager.startBluetoothLyricsUpdate()
                }
            }
        }
        serviceScope.launch {
            prefs.bluetoothCarDeviceNames.collect {
                carAudioManager.bluetoothCarDeviceNames = it
                if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                    carAudioManager.startBluetoothLyricsUpdate()
                }
            }
        }
    }

    private fun setupPlayerListeners(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorHandler.handlePlayerError(player, error)
            }

            private var currentActiveMediaId: String? = null

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) {
                    persistence.savePlaybackState(player)
                    return
                }

                val isSameAsPrevious = mediaItem.mediaId == currentActiveMediaId
                val isRepeat = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                
                if (isSameAsPrevious && !isRepeat) {
                    persistence.savePlaybackState(player)
                    return
                }
                
                currentActiveMediaId = mediaItem.mediaId

                triggerSilentCacheWithDelay(mediaItem)
                if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled && carAudioManager.isCarBluetoothConnected()) {
                    carAudioManager.loadLyricsForBluetooth(mediaItem)
                }
                persistence.savePlaybackState(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    persistence.savePlaybackQueue(player)
                    persistence.savePlaybackState(player)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) = persistence.savePlaybackState(player)
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persistence.savePlaybackState(player)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorHandler.resetErrorCount()
                }
                if (!isDefaultFocusHandling) {
                    if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        abandonAudioFocus()
                        isTransientLossActive = false
                        playWhenReadyBeforeLoss = false
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    persistence.savePlaybackState(player, immediate = true)
                    persistence.stopPositionPersistence()
                    carAudioManager.stopBluetoothLyricsUpdate()
                    if (!isDefaultFocusHandling) {
                        if (!isTransientLossActive) {
                            abandonAudioFocus()
                        }
                    }
                } else {
                    persistence.startPositionPersistence(player)
                    if (carAudioManager.carBluetoothEnabled && carAudioManager.bluetoothLyricsEnabled) {
                        carAudioManager.startBluetoothLyricsUpdate()
                    }
                    if (!isDefaultFocusHandling) {
                        val result = requestAudioFocus()
                        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            player.playWhenReady = false
                        } else {
                            isTransientLossActive = false
                        }
                    }
                }
            }
        })
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
        if (!isDefaultFocusHandling) {
            abandonAudioFocus()
        }
        mediaSession?.run {
            release()
            player.release()
        }
        mediaSession = null
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
            val placeholderPlayer = ExoPlayer.Builder(this).build()
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
