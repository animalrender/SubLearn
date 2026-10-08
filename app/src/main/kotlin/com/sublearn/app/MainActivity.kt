package com.sublearn.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.sublearn.feature.player.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import java.util.Locale

/**
 * The only Activity. SubLearn is a single-Compose-host app on purpose: the player has to keep its
 * surface, its audio session and its PiP state across navigation, and a per-screen Activity would
 * have made that a mess of process-death edge cases.
 */
class MainActivity : ComponentActivity() {
    private val settingsRepository: com.sublearn.core.settings.SettingsRepository by inject()
    private val pipMode = MutableStateFlow(false)
    private val pendingIntent = MutableStateFlow<StartupRequest?>(null)

    private var playerViewModel: PlayerViewModel? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleOverride.wrap(newBase))
    }

    private fun enterPipModeSafely(width: Int, height: Int) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(width.coerceAtLeast(1), height.coerceAtLeast(1)))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.data?.let { uri ->
            pendingIntent.value = StartupRequest(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "video")
        }
        setContent {
            SubLearnApp(
                settingsRepository = settingsRepository,
                pipMode = pipMode.asStateFlow(),
                startup = pendingIntent,
                onStartupConsumed = { pendingIntent.value = null },
                versionName = BuildConfig.VERSION_NAME,
                bindPlayer = { viewModel -> playerViewModel = viewModel },
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            pendingIntent.value = StartupRequest(uri.toString(), uri.lastPathSegment?.substringAfterLast('/') ?: "video")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val player = playerViewModel
        val pipEnabled = settingsRepository.settings.value.player.enterPipOnLeave
        if (pipEnabled && player != null && player.playback.value.isPlaying) {
            val state = player.playback.value
            enterPipModeSafely(
                state.videoWidth.takeIf { it > 0 } ?: 16,
                state.videoHeight.takeIf { it > 0 } ?: 9,
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        pipMode.value = isInPip
    }

    override fun onStop() {
        super.onStop()
        playerViewModel?.onLeave()
    }

    override fun onStart() {
        super.onStart()
        playerViewModel?.onReturn()
    }
}

/** A video handed to the app by another application. */
data class StartupRequest(val uri: String, val title: String)

/**
 * Applies the in-app language choice. API 33 has a system API for this; below it the only reliable
 * option is a configuration override, which is what [LocaleOverride] does for both.
 */
object LocaleOverride {
    fun wrap(base: Context): Context {
        val stored = runCatching { base.getSharedPreferences("sublearn_locale", Context.MODE_PRIVATE).getString("lang", null) }.getOrNull()
        if (stored.isNullOrBlank()) return base
        val locale = Locale.forLanguageTag(stored)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.layoutDirection = if (isRtl(locale)) {
            Configuration.SCREEN_LAYOUT_DIRECTION_RTL
        } else {
            Configuration.SCREEN_LAYOUT_DIRECTION_LTR
        }
        return base.createConfigurationContext(configuration)
    }

    private fun isRtl(locale: Locale): Boolean = locale.language in RTL_LANGUAGES

    fun persist(context: Context, tag: String?) {
        context.getSharedPreferences("sublearn_locale", Context.MODE_PRIVATE).edit()
            .putString("lang", tag)
            .apply()
    }

    private val RTL_LANGUAGES = setOf("fa", "ar", "he", "ur", "ps", "ku", "sd", "yi")
}
