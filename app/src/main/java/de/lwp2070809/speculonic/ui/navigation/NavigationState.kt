package de.lwp2070809.speculonic.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BackStackEntry(
    val key: AppRoute,
    val stack: List<AppRoute>
)

@Serializable
private data class NavigationSaveState(
    val topLevelRoute: AppRoute,
    val backStacks: List<BackStackEntry>
)

@Composable
fun rememberNavigationState(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>
): NavigationState {
    return rememberSaveable(
        saver = NavigationState.saver(startRoute, topLevelRoutes)
    ) {
        NavigationState(startRoute, topLevelRoutes)
    }
}

class NavigationState(
    val startRoute: NavKey,
    val topLevelRoutes: Set<NavKey>,
    initialTopLevelRoute: NavKey = startRoute,
    initialBackStacks: Map<NavKey, List<NavKey>> = mapOf(startRoute to listOf(startRoute))
) {
    var topLevelRoute: NavKey by mutableStateOf(initialTopLevelRoute)
        private set

    var backStacks: Map<NavKey, List<NavKey>> by mutableStateOf(initialBackStacks)
        private set

    fun isTopLevelRoute(route: NavKey) = topLevelRoutes.contains(route)

    fun navigateToTopLevelRoute(route: NavKey) {
        topLevelRoute = route
        if (!backStacks.containsKey(route)) {
            backStacks = backStacks + (route to listOf(route))
        }
    }

    fun popToRootForTopLevelRoute(route: NavKey) {
        if (backStacks.containsKey(route)) {
            backStacks = backStacks + (route to listOf(route))
        }
    }

    fun addRoute(route: NavKey) {
        val stack = backStacks[topLevelRoute] ?: emptyList()
        
        if (stack.isNotEmpty() && stack.last() == route) {
            return
        }
        backStacks = backStacks + (topLevelRoute to (stack + route))
    }

    fun popRoute() {
        val stack = backStacks[topLevelRoute] ?: emptyList()
        if (stack.size > 1) {
            backStacks = backStacks + (topLevelRoute to stack.dropLast(1))
        } else if (topLevelRoute != startRoute) {
            
            navigateToTopLevelRoute(startRoute)
        }
    }

    fun getRetainedKeys(): List<NavKey> {
        val inactiveKeys = topLevelRoutes.mapNotNull {
            if (it != topLevelRoute && backStacks.containsKey(it) && backStacks[it]!!.isNotEmpty()) {
                backStacks[it]!!.last()
            } else {
                null
            }
        }
        
        val activeKeys = backStacks[topLevelRoute]?.takeIf { it.isNotEmpty() } ?: listOf(startRoute)
        return inactiveKeys + activeKeys
    }

    companion object {
        fun saver(
            startRoute: NavKey,
            topLevelRoutes: Set<NavKey>
        ): Saver<NavigationState, String> = Saver(
            save = { state ->
                val topRoute = state.topLevelRoute as? AppRoute ?: startRoute as AppRoute
                val entries = state.backStacks.map { (key, stack) ->
                    BackStackEntry(
                        key = key as? AppRoute ?: startRoute as AppRoute,
                        stack = stack.map { it as? AppRoute ?: startRoute as AppRoute }
                    )
                }
                val saveState = NavigationSaveState(topRoute, entries)
                Json.encodeToString(NavigationSaveState.serializer(), saveState)
            },
            restore = { jsonStr ->
                try {
                    val saveState = Json.decodeFromString(NavigationSaveState.serializer(), jsonStr)
                    val restoredBackStacks = saveState.backStacks.associate { entry ->
                        (entry.key as NavKey) to entry.stack.map { it as NavKey }
                    }
                    NavigationState(
                        startRoute = startRoute,
                        topLevelRoutes = topLevelRoutes,
                        initialTopLevelRoute = saveState.topLevelRoute,
                        initialBackStacks = restoredBackStacks
                    )
                } catch (e: Exception) {
                    NavigationState(startRoute, topLevelRoutes)
                }
            }
        )
    }
}
