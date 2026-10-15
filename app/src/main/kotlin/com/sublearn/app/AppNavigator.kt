package com.sublearn.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sublearn.feature.player.PlayerStart

/**
 * Full-screen destinations. Each route appears in at most one place (§4.2).
 *
 * Bottom nav: HOME, YOUTUBE, LEARN, DICTIONARY
 * Side menu: WORDS, QUIZ, SETTINGS, UPDATES
 */
enum class AppRoute {
    HOME,
    YOUTUBE,
    LEARN,
    DICTIONARY,
    WORDS,
    SETTINGS,
    QUIZ,
    UPDATES,
    PLAYER,
}

/**
 * Hand-rolled navigator with a real backstack (§4.2).
 *
 * The app has one Activity, a player that must survive every navigation, and a handful of
 * destinations. A NavHost would add backstack XML and argument bundles for no benefit here.
 *
 * Navigation rules:
 * - Bottom nav switches (HOME ↔ YOUTUBE ↔ LEARN ↔ DICTIONARY) do NOT push to the backstack.
 * - Every other navigation pushes the current route onto the backstack.
 * - `back()` pops the backstack; if empty on HOME, signals "exit app".
 * - `back()` from PLAYER returns to the route that was active before the player opened.
 * - `openPlayer` saves the current route as `returnRoute`.
 */
@Stable
class AppNavigator {
    var route by mutableStateOf(AppRoute.HOME)
        private set

    var playerStart by mutableStateOf<PlayerStart?>(null)
        private set

    /** Where back returns after the player. */
    var returnRoute by mutableStateOf(AppRoute.HOME)
        private set

    /** Whether a double-back should exit the app (only on HOME with empty stack). */
    var exitRequested by mutableStateOf(false)
        private set

    private val backstack = mutableStateListOf<AppRoute>()

    /** Top-level routes that appear in the bottom nav bar. */
    private val bottomNavRoutes = setOf(
        AppRoute.HOME, AppRoute.YOUTUBE, AppRoute.LEARN, AppRoute.DICTIONARY,
    )

    /**
     * Navigate to a destination.
     * Bottom-nav routes switch without stacking; all other routes push the current onto the stack.
     */
    fun go(destination: AppRoute) {
        exitRequested = false
        if (destination == route) return

        if (destination in bottomNavRoutes) {
            // Bottom nav switch: clear the backstack (leaving a top-level screen)
            backstack.clear()
        } else {
            // Sub-screen: push current route
            backstack.add(route)
        }
        route = destination
    }

    fun openPlayer(uri: String, title: String) {
        exitRequested = false
        returnRoute = if (route == AppRoute.PLAYER) returnRoute else route
        playerStart = PlayerStart(uri = uri, title = title)
        route = AppRoute.PLAYER
    }

    /**
     * Go back. Returns true if the app should handle the back itself (e.g. exit),
     * false if the navigator consumed it.
     */
    fun back(): Boolean {
        exitRequested = false
        if (route == AppRoute.PLAYER) {
            route = returnRoute
            return false
        }
        if (backstack.isNotEmpty()) {
            route = backstack.removeAt(backstack.lastIndex)
            return false
        }
        // On a bottom-nav route with empty stack
        if (route == AppRoute.HOME) {
            exitRequested = true
            return true // caller should handle (double-back to exit)
        }
        // Navigate to HOME from any bottom-nav route
        route = AppRoute.HOME
        return false
    }

    /** Clear exit flag after it's been consumed. */
    fun clearExitRequest() {
        exitRequested = false
    }

    /** The active tab index derived from the current route (§4.2). */
    val activeTabIndex: Int
        get() = when (route) {
            AppRoute.HOME -> 0
            AppRoute.YOUTUBE -> 1
            AppRoute.LEARN -> 2
            AppRoute.DICTIONARY -> 3
            else -> -1 // not on a bottom-nav route
        }
}
