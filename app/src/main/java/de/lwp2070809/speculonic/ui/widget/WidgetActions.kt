package de.lwp2070809.speculonic.ui.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.lwp2070809.speculonic.playback.toggleCurrentItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.lwp2070809.speculonic.data.PreferencesManager
import de.lwp2070809.speculonic.data.db.AppDatabase
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.playback.PlaybackController
import de.lwp2070809.speculonic.util.LogManager

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun subsonicRepository(): SubsonicRepository
    fun playbackController(): PlaybackController
    fun appDatabase(): AppDatabase
    fun preferencesManager(): PreferencesManager
}

/**
 * 微件动作统一来源参数键，用于为每个 PendingIntent 生成互不相同的 Extras，杜绝系统底层 filterEquals 碰撞
 */
object WidgetActionParams {
    val PARAM_ACTION_NAME = ActionParameters.Key<String>("widget_action_name")
}

private fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/**
 * 播放/暂停控制动作回调，支持在冷启动时唤醒就绪并自愈加载完整持久化播放队列
 */
class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        LogManager.i("SpeculonicWidgetAction: WidgetPlayPauseAction triggered")
        val controller = widgetEntryPoint(context).playbackController()
        var needsAutoRestore = false
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.mediaItemCount > 0) {
                ctrl.toggleCurrentItem()
            } else {
                needsAutoRestore = true
            }
        }

        if (needsAutoRestore) {
            controller.restoreQueueAndPlay(playWhenReady = true)
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
        val controller = widgetEntryPoint(context).playbackController()
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.mediaItemCount > 0) {
                ctrl.seekToNext()
                if (!ctrl.playWhenReady) {
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
        val controller = widgetEntryPoint(context).playbackController()
        controller.executeOnControllerAsync { ctrl ->
            if (ctrl.mediaItemCount > 0) {
                ctrl.seekToPrevious()
                if (!ctrl.playWhenReady) {
                    ctrl.play()
                }
            }
        }
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }
}
