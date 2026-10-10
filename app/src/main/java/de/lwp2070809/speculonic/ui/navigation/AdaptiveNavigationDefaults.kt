package de.lwp2070809.speculonic.ui.navigation

import androidx.compose.ui.unit.dp

/**
 * 响应式多窗格与自适应导航的通用设计规范常量。
 */
object AdaptiveNavigationDefaults {
    /**
     * Material 3 Adaptive ListDetail 布局中 ListPane 的基准首选宽度 (360.dp)。
     * 在运行时优先使用 PaneScaffoldDirective.defaultPanePreferredWidth（如宽屏平板 >=1200dp 时为 412.dp），此常量作为默认兜底后备值。
     */
    val ListPanePreferredWidth = 360.dp
}
