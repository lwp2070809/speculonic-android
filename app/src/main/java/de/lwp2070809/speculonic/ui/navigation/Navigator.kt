package de.lwp2070809.speculonic.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.navigation3.runtime.NavKey

class Navigator(
    private val navigationState: NavigationState,
    private val context: Context? = null
) {
    private var lastNavigationTime = 0L
    private var lastRoute: NavKey? = null

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    fun navigate(route: NavKey, inclusive: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (route == lastRoute && now - lastNavigationTime < 500L) {
            return
        }
        lastRoute = route
        lastNavigationTime = now

        if (navigationState.isTopLevelRoute(route)) {
            if (navigationState.topLevelRoute == route) {
                navigationState.popToRootForTopLevelRoute(route)
            } else {
                navigationState.navigateToTopLevelRoute(route)
            }
        } else {
            navigationState.addRoute(route, inclusive)
        }
    }

    fun goBack() {
        val stack = navigationState.backStacks[navigationState.topLevelRoute] ?: emptyList()
        if (stack.size > 1) {
            navigationState.popRoute()
        } else {
            if (navigationState.topLevelRoute != navigationState.startRoute) {
                navigationState.navigateToTopLevelRoute(navigationState.startRoute)
            } else {
                context?.findActivity()?.moveTaskToBack(true)
            }
        }
        lastRoute = navigationState.getCurrentBackStack().lastOrNull()
        lastNavigationTime = 0L
    }

    fun goBackList() {
        navigationState.popListRoute()
        lastRoute = navigationState.getCurrentBackStack().lastOrNull()
        lastNavigationTime = 0L
    }
}
