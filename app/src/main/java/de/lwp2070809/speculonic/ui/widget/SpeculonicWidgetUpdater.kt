package de.lwp2070809.speculonic.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.android.EntryPointAccessors
import de.lwp2070809.speculonic.playback.PlaybackService
import de.lwp2070809.speculonic.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 桌面微件更新同步器，采用并发安全 Channel.CONFLATED 队列实现 300ms 防抖与冷启动状态自愈
 */
object SpeculonicWidgetUpdater {
    private const val UPDATE_DEBOUNCE_MS = 300L
    private const val COVER_PRELOAD_TARGET_DP = 120
    private const val DEFAULT_WIDGET_ALPHA = 0.85f

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val updateChannel = Channel<Context>(Channel.CONFLATED)
    private val updateMutex = Mutex()

    data class ServiceTrackSnapshot(
        val songId: String,
        val title: String,
        val artist: String,
        val artworkUri: String,
        val isPlaying: Boolean
    )

    private data class WidgetTrackPayload(
        val songId: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUri: String,
        val isPlaying: Boolean,
        val hasTrack: Boolean
    )

    @Volatile
    private var serviceTrackOverride: ServiceTrackSnapshot? = null

    init {
        scope.launch {
            for (context in updateChannel) {
                delay(UPDATE_DEBOUNCE_MS)
                // 消化在 300ms 延时期间可能流入的新触发，直接保留最新上下文
                var latestContext = context
                while (true) {
                    val next = updateChannel.tryReceive().getOrNull() ?: break
                    latestContext = next
                }
                updateWidgetsInternal(latestContext)
            }
        }
    }

    fun updateFromService(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        artworkUri: String,
        isPlaying: Boolean
    ) {
        serviceTrackOverride = ServiceTrackSnapshot(
            songId = songId,
            title = title,
            artist = artist,
            artworkUri = artworkUri,
            isPlaying = isPlaying
        )
        enqueueUpdate(context)
    }

    fun clearServiceTrackOverride() {
        serviceTrackOverride = null
    }

    /**
     * 队列化异步更新（内置 300ms 并发安全防抖）
     */
    fun enqueueUpdate(context: Context) {
        val appContext = context.applicationContext
        updateChannel.trySend(appContext)
    }

    /**
     * 服务销毁或状态异常时立即强制将桌面微件重置为暂停态（跳过 300ms 防抖）
     */
    suspend fun resetPlayingStateImmediate(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        clearServiceTrackOverride()
        updateMutex.withLock {
            try {
                while (updateChannel.tryReceive().isSuccess) {
                    // 清空排队中的旧触发，避免后续防抖刷新覆盖刚写入的暂停态
                }
                if (PlaybackService.isServiceActive && (PlaybackService.isPlayerPlaying || PlaybackService.isPlaybackIntended)) {
                    return@withLock
                }
                clearStalePlayingFlagLocked(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("SpeculonicWidgetUpdater: Failed to reset playing state immediately: ${e.message}")
            }
        }
    }

    /**
     * 应用冷启动时自愈：若进程曾被强杀导致持久化状态仍停留在播放中，立即修正为暂停态
     */
    suspend fun reconcileStalePlayingState(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        updateMutex.withLock {
            try {
                if (PlaybackService.isServiceActive && (PlaybackService.isPlayerPlaying || PlaybackService.isPlaybackIntended)) {
                    return@withLock
                }
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    WidgetEntryPoint::class.java
                )
                if (entryPoint.playbackController().playbackState.value.showPauseButton) {
                    return@withLock
                }
                clearStalePlayingFlagLocked(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("SpeculonicWidgetUpdater: Failed to reconcile stale playing state: ${e.message}")
            }
        }
    }

    private suspend fun clearStalePlayingFlagLocked(context: Context) {
        val glanceAppWidgetManager = GlanceAppWidgetManager(context)
        val glanceIds = glanceAppWidgetManager.getGlanceIds(SpeculonicMusicWidget::class.java)
        if (glanceIds.isEmpty()) return

        val widget = SpeculonicMusicWidget()
        for (glanceId in glanceIds) {
            var wasPlaying = false
            updateAppWidgetState(context, glanceId) { prefs ->
                wasPlaying = prefs[WidgetStateKeys.KEY_IS_PLAYING] ?: false
                if (wasPlaying) {
                    prefs[WidgetStateKeys.KEY_IS_PLAYING] = false
                }
            }
            if (wasPlaying) {
                widget.update(context, glanceId)
            }
        }
    }

    /**
     * 执行微件状态写入与跨进程刷新
     */
    suspend fun updateWidgetsInternal(context: Context) = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            try {
                val glanceAppWidgetManager = GlanceAppWidgetManager(context)
                val glanceIds = glanceAppWidgetManager.getGlanceIds(SpeculonicMusicWidget::class.java)
                if (glanceIds.isEmpty()) return@withLock

                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    WidgetEntryPoint::class.java
                )
                val payload = resolveWidgetTrackPayload(entryPoint)
                preloadWidgetCoverArt(context, payload)
                writePayloadToWidgets(context, glanceIds, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("SpeculonicWidgetUpdater: Failed to update widgets: ${e.message}")
            }
        }
    }

    private suspend fun resolveWidgetTrackPayload(entryPoint: WidgetEntryPoint): WidgetTrackPayload {
        val playbackState = entryPoint.playbackController().playbackState.value
        val db = entryPoint.appDatabase()
        val repo = entryPoint.subsonicRepository()
        val prefs = entryPoint.preferencesManager()

        val activeOverride = if (PlaybackService.isServiceActive) {
            serviceTrackOverride?.takeIf { it.songId.isNotEmpty() }
        } else {
            null
        }
        val initialSongId = activeOverride?.songId ?: playbackState.currentSongId
        val cleanedArtworkUri = activeOverride?.artworkUri?.takeIf { it.isNotEmpty() }
            ?: playbackState.artworkUri?.toString()
            ?: ""
        val isPlaying = PlaybackService.isServiceActive &&
            (activeOverride?.isPlaying ?: PlaybackService.isPlaybackIntended)

        // 冷启动或无当前播放曲目时，自愈尝试从持久化历史中加载最后播放的一首歌
        if (initialSongId.isEmpty()) {
            return resolveLastPlayedWidgetPayload(db, repo, prefs, isPlaying)
        }

        val currentSong = db.musicDao().getSongById(initialSongId)
        val resolvedArtwork = cleanedArtworkUri.ifEmpty {
            currentSong?.coverArt?.let { repo.buildCoverArtUrl(it) } ?: ""
        }
        return WidgetTrackPayload(
            songId = initialSongId,
            title = activeOverride?.title ?: playbackState.currentSongTitle,
            artist = activeOverride?.artist ?: playbackState.currentArtist,
            album = currentSong?.album ?: "",
            artworkUri = resolvedArtwork,
            isPlaying = isPlaying,
            hasTrack = true
        )
    }

    private suspend fun resolveLastPlayedWidgetPayload(
        db: de.lwp2070809.speculonic.data.db.AppDatabase,
        repo: de.lwp2070809.speculonic.domain.repository.SubsonicRepository,
        prefs: de.lwp2070809.speculonic.data.PreferencesManager,
        isPlaying: Boolean
    ): WidgetTrackPayload {
        val lastId = prefs.lastSongId.first() ?: ""
        val lastSong = if (lastId.isNotEmpty()) db.musicDao().getSongById(lastId) else null
        if (lastSong == null) {
            return WidgetTrackPayload("", "", "", "", "", isPlaying, hasTrack = false)
        }
        return WidgetTrackPayload(
            songId = lastSong.id,
            title = lastSong.title,
            artist = lastSong.artist ?: "",
            album = lastSong.album ?: "",
            artworkUri = lastSong.coverArt?.let { repo.buildCoverArtUrl(it) } ?: "",
            isPlaying = isPlaying,
            hasTrack = true
        )
    }

    private suspend fun preloadWidgetCoverArt(context: Context, payload: WidgetTrackPayload) {
        if (!payload.hasTrack) return
        if (payload.artworkUri.isEmpty() && payload.songId.isEmpty()) return
        try {
            WidgetCoverLoader.loadCover(context, payload.artworkUri, payload.songId, targetDp = COVER_PRELOAD_TARGET_DP)
        } catch (coverEx: Exception) {
            LogManager.w("SpeculonicWidgetUpdater: Pre-caching cover failed: ${coverEx.message}")
        }
    }

    private suspend fun writePayloadToWidgets(
        context: Context,
        glanceIds: List<androidx.glance.GlanceId>,
        payload: WidgetTrackPayload
    ) {
        val widget = SpeculonicMusicWidget()
        for (glanceId in glanceIds) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetStateKeys.KEY_SONG_ID] = payload.songId
                prefs[WidgetStateKeys.KEY_TITLE] = payload.title
                prefs[WidgetStateKeys.KEY_ARTIST] = payload.artist
                prefs[WidgetStateKeys.KEY_ALBUM] = payload.album
                prefs[WidgetStateKeys.KEY_ARTWORK_URI] = payload.artworkUri
                prefs[WidgetStateKeys.KEY_IS_PLAYING] = payload.isPlaying
                prefs[WidgetStateKeys.KEY_HAS_TRACK] = payload.hasTrack
                if (!prefs.contains(WidgetStateKeys.KEY_BACKGROUND_ALPHA)) {
                    prefs[WidgetStateKeys.KEY_BACKGROUND_ALPHA] = DEFAULT_WIDGET_ALPHA
                }
            }
            widget.update(context, glanceId)
        }
    }
}
