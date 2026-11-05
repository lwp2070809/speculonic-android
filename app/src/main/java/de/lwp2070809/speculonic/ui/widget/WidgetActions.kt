package de.lwp2070809.speculonic.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogManager
import de.lwp2070809.speculonic.util.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun subsonicRepository(): SubsonicRepository
}

/**
 * 微件动作统一来源参数键，用于为每个 PendingIntent 生成互不相同的 Extras，杜绝系统底层 filterEquals 碰撞
 */
object WidgetActionParams {
    val PARAM_ACTION_NAME = ActionParameters.Key<String>("widget_action_name")
}

/**
 * 播放/暂停控制动作回调，支持在冷启动时唤醒就绪并自愈加载最后播放歌曲
 */
class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        LogManager.i("SpeculonicWidgetAction: WidgetPlayPauseAction triggered")
        val controller = PlaybackController.getInstance(context)
        var needsAutoRestore = false
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.isPlaying) {
                ctrl.pause()
            } else if (ctrl.mediaItemCount > 0) {
                if (ctrl.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    ctrl.prepare()
                }
                ctrl.play()
            } else {
                needsAutoRestore = true
            }
        }

        // 若冷启动恢复后队列为空，在结构化挂起流程内自愈从持久化历史恢复最后播放曲目（杜绝非结构化协程被系统斩杀）
        if (needsAutoRestore) {
            withContext(Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    val prefs = PreferencesManager.getInstance(appContext)
                    val lastId = prefs.lastSongId.first() ?: ""
                    if (lastId.isNotEmpty()) {
                        val db = AppDatabase.getDatabase(appContext)
                        val song = db.musicDao().getSongById(lastId)
                        if (song != null) {
                            val entryPoint = EntryPointAccessors.fromApplication(
                                appContext,
                                WidgetEntryPoint::class.java
                            )
                            val repo = entryPoint.subsonicRepository()
                            repo.ensureConfigured()
                            val mediaItem = song.toMediaItem(repo)
                            controller.executeOnControllerAsync { ctrl ->
                                ctrl.setMediaItem(mediaItem)
                                ctrl.prepare()
                                ctrl.play()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.w("WidgetPlayPauseAction: Auto-restoring last song failed: ${e.message}")
                }
            }
        }
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }
}

/**
 * 下一首控制动作回调
 */
class WidgetNextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        LogManager.i("SpeculonicWidgetAction: WidgetNextAction triggered")
        val controller = PlaybackController.getInstance(context)
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.mediaItemCount > 0) {
                ctrl.seekToNext()
                if (!ctrl.isPlaying) {
                    ctrl.play()
                }
            }
        }
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }
}

/**
 * 上一首控制动作回调
 */
class WidgetPreviousAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        LogManager.i("SpeculonicWidgetAction: WidgetPreviousAction triggered")
        val controller = PlaybackController.getInstance(context)
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.mediaItemCount > 0) {
                ctrl.seekToPrevious()
                if (!ctrl.isPlaying) {
                    ctrl.play()
                }
            }
        }
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }
}
