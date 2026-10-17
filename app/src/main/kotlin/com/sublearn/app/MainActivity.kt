package com.sublearn.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        intent?.let(::startupRequest)?.let { pendingIntent.value = it }
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
        startupRequest(intent)?.let { pendingIntent.value = it }
    }


    private fun startupRequest(intent: android.content.Intent): StartupRequest? {
        val uri = intent.data
            ?: intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri
            ?: return null
        if (intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        ) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return StartupRequest(uri.toString(), displayNameOf(uri))
    }

    /**
     * The file's real name, as the provider reports it. A `content://media/…/1234` URI has a
     * numeric last segment, and the player's title bar and the recent list must show the file name
     * the user knows, not an id or a cleaned-up guess.
     */
    private fun displayNameOf(uri: android.net.Uri): String {
        return com.sublearn.core.player.DocumentNames.read(this, uri, "video")
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
        // Closing the PiP window stops the Activity without a second onStop; that close is also
        // "leaving the player", so the film pauses here instead of carrying on as audio only.
        if (!isInPip && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            playerViewModel?.onLeave(pausePlayback = true)
        }
    }

    override fun onStop() {
        super.onStop()
        // Going to the background pauses the film unless it is playing in a PiP window (D-84).
        playerViewModel?.onLeave(pausePlayback = !isInPictureInPictureMode)
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
        // The layout direction follows the locale (Persian is RTL); the platform knows the script.
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    fun persist(context: Context, tag: String?) {
        context.getSharedPreferences("sublearn_locale", Context.MODE_PRIVATE).edit()
            .putString("lang", tag)
            .apply()
    }
}
