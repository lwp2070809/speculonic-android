package de.lwp2070809.speculonic.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.android.EntryPointAccessors
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.playback.PlaybackController
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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val updateChannel = Channel<Context>(Channel.CONFLATED)
    private val updateMutex = Mutex()

    init {
        scope.launch {
            for (context in updateChannel) {
                delay(300)
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
        updateMutex.withLock {
            try {
                while (updateChannel.tryReceive().isSuccess) {
                    // 清空排队中的旧触发，避免后续防抖刷新覆盖刚写入的暂停态
                }
                if (PlaybackService.isServiceActive && PlaybackService.isPlayerPlaying) {
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
                if (PlaybackService.isServiceActive && PlaybackService.isPlayerPlaying) {
                    return@withLock
                }
                if (PlaybackController.getInstance(appContext).playbackState.value.isPlaying) {
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

                val controller = PlaybackController.getInstance(context)
                val playbackState = controller.playbackState.value
                val db = AppDatabase.getDatabase(context)

                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    WidgetEntryPoint::class.java
                )
                val repo = entryPoint.subsonicRepository()

                var songId = playbackState.currentSongId
                var title = playbackState.currentSongTitle
                var artist = playbackState.currentArtist
                var album = ""
                var artworkUri = playbackState.artworkUri?.toString() ?: ""
                var isPlaying = PlaybackService.isServiceActive && PlaybackService.isPlayerPlaying
                var hasTrack = songId.isNotEmpty()

                // 冷启动或无当前播放曲目时，自愈尝试从持久化历史中加载最后播放的一首歌
                if (!hasTrack) {
                    val prefs = PreferencesManager.getInstance(context)
                    val lastId = prefs.lastSongId.first() ?: ""
                    if (lastId.isNotEmpty()) {
                        val lastSong = db.musicDao().getSongById(lastId)
                        if (lastSong != null) {
                            songId = lastSong.id
                            title = lastSong.title
                            artist = lastSong.artist ?: ""
                            album = lastSong.album ?: ""
                            artworkUri = lastSong.coverArt?.let { repo.buildCoverArtUrl(it) } ?: ""
                            hasTrack = true
                        }
                    }
                } else {
                    val currentSong = db.musicDao().getSongById(songId)
                    if (currentSong != null) {
                        album = currentSong.album ?: ""
                        if (artworkUri.isEmpty()) {
                            artworkUri = currentSong.coverArt?.let { repo.buildCoverArtUrl(it) } ?: ""
                        }
                    }
                }

                if (hasTrack && (artworkUri.isNotEmpty() || songId.isNotEmpty())) {
                    try {
                        WidgetCoverLoader.loadCover(context, artworkUri, songId, targetDp = 120)
                    } catch (coverEx: Exception) {
                        LogManager.w("SpeculonicWidgetUpdater: Pre-caching cover failed: ${coverEx.message}")
                    }
                }

                val widget = SpeculonicMusicWidget()
                for (glanceId in glanceIds) {
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[WidgetStateKeys.KEY_SONG_ID] = songId
                        prefs[WidgetStateKeys.KEY_TITLE] = title
                        prefs[WidgetStateKeys.KEY_ARTIST] = artist
                        prefs[WidgetStateKeys.KEY_ALBUM] = album
                        prefs[WidgetStateKeys.KEY_ARTWORK_URI] = artworkUri
                        prefs[WidgetStateKeys.KEY_IS_PLAYING] = isPlaying
                        prefs[WidgetStateKeys.KEY_HAS_TRACK] = hasTrack
                        if (!prefs.contains(WidgetStateKeys.KEY_BACKGROUND_ALPHA)) {
                            prefs[WidgetStateKeys.KEY_BACKGROUND_ALPHA] = 0.85f
                        }
                    }
                    widget.update(context, glanceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w("SpeculonicWidgetUpdater: Failed to update widgets: ${e.message}")
            }
        }
    }
}
