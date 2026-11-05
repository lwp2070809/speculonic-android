package de.lwp2070809.speculonic.ui.widget

import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import de.lwp2070809.speculonic.ui.theme.DarkColorScheme
import de.lwp2070809.speculonic.ui.theme.LightColorScheme

/**
 * Speculonic 桌面微件 Material 3 色彩提供者，确保微件全面接入 App 颜色体系
 */
object SpeculonicWidgetGlanceColorScheme {
    val colors: ColorProviders = ColorProviders(
        light = LightColorScheme,
        dark = DarkColorScheme
    )
}
