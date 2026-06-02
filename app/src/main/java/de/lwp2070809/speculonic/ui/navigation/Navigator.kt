package de.lwp2070809.speculonic.ui.navigation

import androidx.navigation3.runtime.NavKey

class Navigator(
    private val navigationState: NavigationState
) {
    fun navigate(route: NavKey) {
        if (navigationState.isTopLevelRoute(route)) {
            if (navigationState.topLevelRoute == route) {
                
                navigationState.popToRootForTopLevelRoute(route)
            } else {
                
                navigationState.navigateToTopLevelRoute(route)
            }
        } else {
            
            navigationState.addRoute(route)
        }
    }

    fun goBack() {
        val stack = navigationState.backStacks[navigationState.topLevelRoute] ?: emptyList()
        if (stack.size > 1) {
            navigationState.popRoute()
        }
        
        
    }
}
