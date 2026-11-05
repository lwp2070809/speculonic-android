package de.lwp2070809.speculonic.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import de.lwp2070809.speculonic.playback.PlaybackService
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as UnitColorProvider
import de.lwp2070809.speculonic.MainActivity
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.util.LogManager

/**
 * 满足 Android 17 (API 37) RemoteViews 位图内存限制的音乐控制桌面微件
 * 严格限制在 1*3 与 1*4 之间横向自适应调节，提供零穿透的显式媒体信息点击与精准控制
 */
class SpeculonicMusicWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Responsive(RESPONSIVE_BUCKETS)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialPrefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val initialArtworkUri = initialPrefs[WidgetStateKeys.KEY_ARTWORK_URI] ?: ""
        val initialSongId = initialPrefs[WidgetStateKeys.KEY_SONG_ID] ?: ""

        // 阶段一：在会话启动时预热初始封面位图
        if (initialSongId.isNotEmpty() || initialArtworkUri.isNotEmpty()) {
            WidgetCoverLoader.loadCover(
                context = context,
                uriString = initialArtworkUri,
                songId = initialSongId,
                targetDp = 120
            )
        }

        provideContent {
            val currentPrefs = currentState<Preferences>()
            val isTrackAvailable = currentPrefs[WidgetStateKeys.KEY_HAS_TRACK] ?: false
            val title = currentPrefs[WidgetStateKeys.KEY_TITLE] ?: ""
            val artist = currentPrefs[WidgetStateKeys.KEY_ARTIST] ?: ""
            val album = currentPrefs[WidgetStateKeys.KEY_ALBUM] ?: ""
            val isPlaying = currentPrefs[WidgetStateKeys.KEY_IS_PLAYING] ?: false
            val currentSongId = currentPrefs[WidgetStateKeys.KEY_SONG_ID] ?: ""
            val currentArtworkUri = currentPrefs[WidgetStateKeys.KEY_ARTWORK_URI] ?: ""
            val backgroundAlpha = currentPrefs[WidgetStateKeys.KEY_BACKGROUND_ALPHA] ?: 0.85f
            val size = LocalSize.current

            // 阶段二：纯内存 O(1) 瞬时查询，未命中优雅展示音符占位图，杜绝 Compose 组合期间的阻塞 IO 与外部状态变异
            val currentCoverBitmap = WidgetCoverLoader.getCoverFromCache(
                songId = currentSongId,
                uriString = currentArtworkUri
            )

            // 适配系统深浅色模式与用户设定透明度的微件卡片底色，对齐 MiniPlayer 的 surfaceContainerHighest 视觉层级
            val containerColor = ColorProvider(
                day = Color(0xFFEBE6EE).copy(alpha = backgroundAlpha),
                night = Color(0xFF211E27).copy(alpha = backgroundAlpha)
            )

            val widgetColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GlanceTheme.colors
            } else {
                SpeculonicWidgetGlanceColorScheme.colors
            }

            GlanceTheme(colors = widgetColors) {
                // 遵循官方规范直接使用 actionStartActivity，杜绝经过广播 Trampoline 转发产生串线与延迟
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = if (isTrackAvailable) PlaybackService.ACTION_SHOW_PLAYER else Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val cardClickAction = actionStartActivity(openIntent)

                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .appWidgetBackground()
                        .background(containerColor)
                        .cornerRadius(16.dp)
                        .clickable(cardClickAction)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isTrackAvailable) {
                        EmptyWidgetContent(context, cardClickAction)
                    } else {
                        // 阈值以 270dp（约 4 格标准尺寸）为界限，在 1*3（标准模式）与 1*4（展开模式）之间自适应调节
                        if (size.width < 270.dp) {
                            StandardWidgetContent(
                                optimizedCover = currentCoverBitmap,
                                title = title,
                                artist = artist,
                                isPlaying = isPlaying,
                                onCardClick = cardClickAction
                            )
                        } else {
                            ExpandedWidgetContent(
                                optimizedCover = currentCoverBitmap,
                                title = title,
                                artist = artist,
                                album = album,
                                isPlaying = isPlaying,
                                onCardClick = cardClickAction
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StandardWidgetContent(
        optimizedCover: Bitmap?,
        title: String,
        artist: String,
        isPlaying: Boolean,
        onCardClick: androidx.glance.action.Action
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧媒体信息区（封面 + 歌曲标题 + 歌手）：显式绑定 onCardClick，直接消费点击事件，彻底根除点击标题误触为下一首的穿透 Bug
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(onCardClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WidgetCoverArt(optimizedCover, sizeDp = 46)
                Spacer(modifier = GlanceModifier.width(8.dp))
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.ifEmpty { "..." },
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    )
                    if (artist.isNotEmpty()) {
                        Text(
                            text = artist,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.secondary,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.width(6.dp))

            // 右侧播放与切歌控制组
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                WidgetControlButton(
                    iconRes = if (isPlaying) R.drawable.ic_symbol_pause else R.drawable.ic_symbol_play_arrow,
                    contentDescription = if (isPlaying) R.string.widget_action_pause else R.string.widget_action_play,
                    onClick = actionRunCallback<WidgetPlayPauseAction>(
                        actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "play_pause")
                    ),
                    tintColor = GlanceTheme.colors.primary
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                WidgetControlButton(
                    iconRes = R.drawable.ic_symbol_skip_next,
                    contentDescription = R.string.widget_action_next,
                    onClick = actionRunCallback<WidgetNextAction>(
                        actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "next")
                    ),
                    tintColor = GlanceTheme.colors.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun ExpandedWidgetContent(
        optimizedCover: Bitmap?,
        title: String,
        artist: String,
        album: String,
        isPlaying: Boolean,
        onCardClick: androidx.glance.action.Action
    ) {
        val subtitle = when {
            artist.isNotEmpty() && album.isNotEmpty() -> "$artist • $album"
            artist.isNotEmpty() -> artist
            album.isNotEmpty() -> album
            else -> ""
        }

        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧媒体信息区（封面 + 歌曲标题 + 歌手/专辑）：显式绑定 onCardClick，直接消费点击事件
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(onCardClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WidgetCoverArt(optimizedCover, sizeDp = 48)
                Spacer(modifier = GlanceModifier.width(10.dp))
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.ifEmpty { "..." },
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            style = TextStyle(
                                color = GlanceTheme.colors.secondary,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.width(6.dp))

            // 右侧播放控制组（上一首、播放/暂停、下一首）
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                WidgetControlButton(
                    iconRes = R.drawable.ic_symbol_skip_previous,
                    contentDescription = R.string.widget_action_previous,
                    onClick = actionRunCallback<WidgetPreviousAction>(
                        actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "previous")
                    ),
                    tintColor = GlanceTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                WidgetControlButton(
                    iconRes = if (isPlaying) R.drawable.ic_symbol_pause else R.drawable.ic_symbol_play_arrow,
                    contentDescription = if (isPlaying) R.string.widget_action_pause else R.string.widget_action_play,
                    onClick = actionRunCallback<WidgetPlayPauseAction>(
                        actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "play_pause")
                    ),
                    tintColor = GlanceTheme.colors.primary
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
                WidgetControlButton(
                    iconRes = R.drawable.ic_symbol_skip_next,
                    contentDescription = R.string.widget_action_next,
                    onClick = actionRunCallback<WidgetNextAction>(
                        actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "next")
                    ),
                    tintColor = GlanceTheme.colors.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun EmptyWidgetContent(context: Context, onCardClick: androidx.glance.action.Action) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(onCardClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(44.dp)
                        .background(GlanceTheme.colors.surfaceVariant)
                        .cornerRadius(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_symbol_music_note),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                        modifier = GlanceModifier.size(24.dp)
                    )
                }
                Spacer(modifier = GlanceModifier.width(10.dp))
                Text(
                    text = context.getString(R.string.widget_no_playing),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp
                    ),
                    modifier = GlanceModifier.defaultWeight()
                )
            }
            Spacer(modifier = GlanceModifier.width(6.dp))
            WidgetControlButton(
                iconRes = R.drawable.ic_symbol_play_arrow,
                contentDescription = R.string.widget_action_play,
                onClick = actionRunCallback<WidgetPlayPauseAction>(
                    actionParametersOf(WidgetActionParams.PARAM_ACTION_NAME to "play_pause")
                ),
                tintColor = GlanceTheme.colors.primary
            )
        }
    }

    @Composable
    private fun WidgetCoverArt(optimizedCover: Bitmap?, sizeDp: Int) {
        if (optimizedCover != null) {
            Image(
                provider = ImageProvider(optimizedCover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier
                    .size(sizeDp.dp)
                    .cornerRadius(8.dp)
            )
        } else {
            Box(
                modifier = GlanceModifier
                    .size(sizeDp.dp)
                    .background(GlanceTheme.colors.surfaceVariant)
                    .cornerRadius(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_symbol_music_note),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size((sizeDp / 2).dp)
                )
            }
        }
    }

    @Composable
    private fun WidgetControlButton(
        iconRes: Int,
        @androidx.annotation.StringRes contentDescription: Int,
        onClick: androidx.glance.action.Action,
        tintColor: UnitColorProvider = GlanceTheme.colors.onSurfaceVariant
    ) {
        val context = androidx.glance.LocalContext.current
        // 设置 36dp 可点击外层尺寸，内部 padding 6dp 带来良好的无障碍触摸热区，同时注入无障碍语义
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = context.getString(contentDescription),
            colorFilter = ColorFilter.tint(tintColor),
            modifier = GlanceModifier
                .size(36.dp)
                .cornerRadius(18.dp)
                .clickable(onClick)
                .padding(6.dp)
        )
    }

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        LogManager.e("SpeculonicMusicWidget: Composition error on widget #$appWidgetId", throwable)
        val fallback = RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, context.getString(R.string.widget_name))
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setOnClickPendingIntent(android.R.id.text1, pendingIntent)
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, fallback)
    }

    companion object {
        val RESPONSIVE_BUCKETS = setOf(
            DpSize(180.dp, 56.dp), // 1*3 标准模式
            DpSize(270.dp, 56.dp)  // 1*4 展开模式
        )
    }
}
