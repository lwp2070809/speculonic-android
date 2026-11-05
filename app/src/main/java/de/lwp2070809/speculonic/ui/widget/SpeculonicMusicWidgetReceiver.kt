package de.lwp2070809.speculonic.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Speculonic 桌面微件广播接收器
 */
class SpeculonicMusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpeculonicMusicWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SpeculonicWidgetUpdater.enqueueUpdate(context)
    }
}
