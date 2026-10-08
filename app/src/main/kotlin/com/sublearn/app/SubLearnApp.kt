package com.sublearn.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloseSmall
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.common.FeatureFlag
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.SubLearnTheme
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.lexicon.LaterCapabilities
import com.sublearn.feature.home.HomeScreen
import com.sublearn.feature.home.HomeViewModel
import com.sublearn.feature.learn.LearnScreen
import com.sublearn.feature.player.PlayerScreen
import com.sublearn.feature.player.PlayerViewModel
import com.sublearn.feature.settings.SettingsScreen
import com.sublearn.feature.words.MyWordsScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * App shell: theme, drawer, the four tabs and the routes.
 *
 * A section whose flag is off shows an honest "coming soon" card rather than an empty screen, which
 * keeps the navigation complete while [FeatureFlag] stays the single source of truth.
 */
@Composable
fun SubLearnApp(
    settingsRepository: SettingsRepository,
    startup: StateFlow<StartupRequest?>,
    pipMode: StateFlow<Boolean>,
    onStartupConsumed: () -> Unit,
    versionName: String,
    bindPlayer: (PlayerViewModel) -> Unit,
) {
    val inPip by pipMode.collectAsStateWithLifecycle(initialValue = false)
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    SubLearnTheme(settings = settings) {
        val nav = remember { AppNavigator() }
        val playerViewModel: PlayerViewModel = koinViewModel()
        LaunchedEffect(playerViewModel) { bindPlayer(playerViewModel) }

        val request by startup.collectAsStateWithLifecycle(initialValue = null)
        LaunchedEffect(request) {
            request?.let {
                nav.openPlayer(it.uri, it.title)
                onStartupConsumed()
            }
        }

        if (nav.route == AppRoute.PLAYER) {
            PlayerScreen(
                viewModel = playerViewModel,
                onBack = nav::back,
                onOpenWords = { nav.go(AppRoute.WORDS) },
                onOpenSettings = { nav.go(AppRoute.SETTINGS) },
                start = nav.playerStart,
                inPip = inPip,
            )
            return@SubLearnTheme
        }

        AppScaffold(nav = nav)
    }
}

@Composable
private fun AppScaffold(nav: AppNavigator) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var tab by remember { mutableStateOf(Tab.HOME) }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.padding(vertical = 20.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.size(16.dp))
                MenuEntry(Icons.Default.School, R.string.menu_level) {
                    scope.launch { drawerState.close() }
                    nav.go(AppRoute.LEVEL)
                }
                MenuEntry(Icons.Default.Star, R.string.menu_my_words) {
                    scope.launch { drawerState.close() }
                    nav.go(AppRoute.WORDS)
                }
                MenuEntry(Icons.Default.AutoStories, R.string.menu_quiz) {
                    scope.launch { drawerState.close() }
                    nav.go(AppRoute.QUIZ)
                }
                MenuEntry(Icons.Default.TrendingUp, R.string.settings_category_dictionary) {
                    scope.launch { drawerState.close() }
                    nav.go(AppRoute.DICTIONARY)
                }
                MenuEntry(Icons.Default.Settings, R.string.menu_settings) {
                    scope.launch { drawerState.close() }
                    nav.go(AppRoute.SETTINGS)
                }
            }
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(nav.route.titleRes()), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_settings))
                        }
                    },
                    actions = {
                        // The player is the only screen that needs the drawer closed, so it hides the bar.
                        androidx.compose.material3.IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.CloseSmall, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TabEntry(tab == Tab.HOME, Icons.Default.OndemandVideo, R.string.tab_home) {
                        tab = Tab.HOME
                        nav.go(AppRoute.HOME)
                    }
                    TabEntry(tab == Tab.YOUTUBE, Icons.Default.SmartDisplay, R.string.tab_youtube) {
                        tab = Tab.YOUTUBE
                        nav.go(AppRoute.YOUTUBE)
                    }
                    TabEntry(tab == Tab.LEARN, Icons.Default.Slideshow, R.string.tab_learn) {
                        tab = Tab.LEARN
                        nav.go(AppRoute.LEARN)
                    }
                    TabEntry(tab == Tab.DICTIONARY, Icons.Default.TrendingUp, R.string.tab_dictionary) {
                        tab = Tab.DICTIONARY
                        nav.go(AppRoute.DICTIONARY)
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (nav.route) {
                    AppRoute.HOME -> HomeScreen(
                        onPlay = { uri, title -> nav.openPlayer(uri, title) },
                        onOpenUrl = { url -> nav.openPlayer(url, url.substringAfterLast('/')) },
                        viewModel = koinViewModel<HomeViewModel>(),
                    )

                    AppRoute.LEARN -> LearnScreen(
                        onOpenWords = { nav.go(AppRoute.WORDS) },
                        onOpenSettings = { nav.go(AppRoute.SETTINGS) },
                    )

                    AppRoute.WORDS -> MyWordsScreen(onBack = { nav.go(AppRoute.HOME) })
                    AppRoute.SETTINGS -> SettingsScreen(
                        onBack = { nav.go(AppRoute.HOME) },
                        versionName = versionName,
                    )
                    AppRoute.LEVEL -> SettingsScreen(
                        onBack = { nav.go(AppRoute.HOME) },
                        startSection = "learning",
                        versionName = versionName,
                    )
                    AppRoute.YOUTUBE -> ComingSoonScreen(FeatureFlag.YOUTUBE)
                    AppRoute.DICTIONARY -> ComingSoonScreen(FeatureFlag.OFFLINE_DICTIONARY)
                    AppRoute.QUIZ -> ComingSoonScreen(FeatureFlag.QUIZ)
                    AppRoute.UPDATES -> ComingSoonScreen(FeatureFlag.UPDATE_CHECKER)
                    AppRoute.PLAYER -> Unit
                }
            }
        }
    }
}

private enum class Tab { HOME, YOUTUBE, LEARN, DICTIONARY }

private fun AppRoute.titleRes(): Int = when (this) {
    AppRoute.HOME -> R.string.tab_home
    AppRoute.YOUTUBE -> R.string.tab_youtube
    AppRoute.LEARN -> R.string.tab_learn
    AppRoute.DICTIONARY -> R.string.tab_dictionary
    AppRoute.WORDS -> R.string.words_title
    AppRoute.SETTINGS -> R.string.settings_title
    AppRoute.LEVEL -> R.string.menu_level
    AppRoute.QUIZ -> R.string.menu_quiz
    AppRoute.UPDATES -> R.string.menu_check_updates
    AppRoute.PLAYER -> R.string.app_name
}

@Composable
private fun MenuEntry(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) },
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TabEntry(selected: Boolean, icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(labelRes)) },
    )
}

/** What a disabled section shows: what it will do, and no fake controls. */
@Composable
fun ComingSoonScreen(flag: FeatureFlag, modifier: Modifier = Modifier) {
    val entry = remember(flag) { LaterCapabilities.entries.firstOrNull { it.flag == flag } }
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            entry?.detail ?: stringResource(R.string.coming_soon_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
