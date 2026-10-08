package com.sublearn.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sublearn.feature.player.PlayerStart

/** Which full-screen destination is showing. */
enum class AppRoute {
    HOME,
    YOUTUBE,
    LEARN,
    DICTIONARY,
    WORDS,
    SETTINGS,
    LEVEL,
    QUIZ,
    UPDATES,
    PLAYER,
}

/**
 * Hand-rolled navigation, deliberately.
 *
 * The app has one Activity, a player that must survive every navigation, and a handful of
 * destinations; a NavHost would add backstack XML and argument bundles for no benefit here. Deep
 * links arrive through [MainActivity]'s intent handling instead. A richer navigation graph is the
 * first thing to swap in when a section with sub-screens (PDF, dictionary editor) lands.
 */
@Stable
class AppNavigator {
    var route by mutableStateOf(AppRoute.HOME)
        private set

    var playerStart by mutableStateOf<PlayerStart?>(null)
        private set

    /** Where the back gesture returns to after the player. */
    var returnRoute by mutableStateOf(AppRoute.HOME)
        private set

    fun go(destination: AppRoute) {
        route = destination
    }

    fun openPlayer(uri: String, title: String) {
        returnRoute = if (route == AppRoute.PLAYER) returnRoute else route
        playerStart = PlayerStart(uri = uri, title = title)
        route = AppRoute.PLAYER
    }

    fun back() {
        route = returnRoute
    }
}
