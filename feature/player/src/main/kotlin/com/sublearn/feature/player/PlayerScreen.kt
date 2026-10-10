package com.sublearn.feature.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings as SystemSettings
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.R
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.OrientationLock

/**
 * The player screen (PLY-1..7).
 *
 * Everything drawn over the picture is laid out in one place, in this order: picture, subtitle
 * layers, the gesture surface, chrome, then transient feedback, and finally sheets and popups.
 * Gestures are resolved by [PlayerHitRegistry], so an element that the user touches always wins over
 * the surface underneath it.
 *
 * Window effects (immersive bars, orientation, brightness, keep-screen-on) are owned here and are
 * restored when the screen leaves composition, so the rest of the app never inherits them.
 * One-shot requests from the ViewModel arrive as [PlayerIntent]s and are handled here, because only
 * the Activity can carry them out.
 */
// The subtitle view is hidden, not used: the picture is shown without Media3 captions, because
// SubLearn draws its own layers. Only that call needs the unstable Media3 API.
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onOpenWords: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    start: PlayerStart? = null,
    inPip: Boolean = false,
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity = context.findActivity()
    val reduceMotion = LocalReduceMotion.current
    val colors = LocalSubLearnColors.current
    val density = LocalDensity.current
    val registry = remember { PlayerHitRegistry() }
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }
    val currentOpenWords by rememberUpdatedState(onOpenWords)
    val currentOpenSettings by rememberUpdatedState(onOpenSettings)
    val subtitlePicker = rememberLauncherForActivityResult(OpenDocumentContract(SUBTITLE_MIMES)) { picked ->
        if (picked != null) {
            val role = SubtitleRoleGuess.guess(picked.name, ui.settings.languages.nativeLanguage).role
            viewModel.loadFile(role, picked.uri, picked.name)
        }
    }

    LaunchedEffect(start?.uri) {
        start?.let { viewModel.open(it.uri, it.title, it.subtitles) }
    }

    // Immersive playback: the bars come back on swipe and are restored when the screen leaves.
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.setScreenBrightness(null)
        }
    }
    LaunchedEffect(ui.settings.player.orientationLock) {
        activity?.requestedOrientation = orientationFor(ui.settings.player.orientationLock)
    }
    val keepScreenOn = ui.settings.player.keepScreenOn
    DisposableEffect(activity, keepScreenOn) {
        val window = activity?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Leaving the screen (or the app) saves the position; coming back rebuilds the chrome.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    viewModel.onLeave()
                }
                Lifecycle.Event.ON_START -> if (stopped) viewModel.onReturn()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onLeave()
            viewRef[0]?.let { viewModel.controller.detachView(it) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.intents.collect { intent ->
            when (intent) {
                is PlayerIntent.Brightness -> activity?.setScreenBrightness(intent.value)
                is PlayerIntent.Volume -> activity?.setSystemVolume(intent.value)
                is PlayerIntent.EnterPip -> activity?.enterPipMode(intent.aspectWidth, intent.aspectHeight)
                is PlayerIntent.Share -> context.startActivity(Intent.createChooser(intent.toShareIntent(), intent.title))
                is PlayerIntent.OpenWords -> currentOpenWords()
                is PlayerIntent.OpenSettings -> currentOpenSettings()
            }
        }
    }

    // Back closes the innermost thing first; only with nothing open does it leave the player.
    BackHandler { handleBackFrom(ui, viewModel, onBack) }

    val rotationLocked = ui.settings.player.orientationLock != OrientationLock.AUTO
    val gestureMode = when {
        inPip || ui.locked -> GestureMode.OFF
        ui.layoutMode -> GestureMode.LAYOUT
        else -> GestureMode.NORMAL
    }
    val tuning = GestureTuning(
        invertVertical = ui.settings.gestures.invertVertical,
        edgeExclusionPx = ui.settings.gestures.edgeExclusionDp * density.density,
        multiTapWindowMs = ui.settings.learning.multiTapWindowMs,
    )
    val readLevel = remember(activity, context) { levelReader(context, activity) }
    val menuActions = listOf(
        PlayerMenuItem(R.string.player_pip, Icons.Default.PictureInPictureAlt, checked = false) { viewModel.enterPip() },
        PlayerMenuItem(R.string.player_share, Icons.Default.Share, checked = false) { viewModel.shareCurrent() },
        PlayerMenuItem(
            label = if (rotationLocked) R.string.player_rotation_unlock else R.string.player_rotation_lock,
            icon = Icons.Default.ScreenRotation,
            checked = rotationLocked,
        ) { viewModel.toggleRotationLock() },
        PlayerMenuItem(R.string.subtitle_layout_mode, Icons.Default.Tune, checked = ui.layoutMode) { viewModel.setLayoutMode(true) },
        PlayerMenuItem(R.string.player_subtitle_options, Icons.Default.Subtitles, checked = false) {
            viewModel.setSheet(Sheet.LAYER_OPTIONS)
        },
        PlayerMenuItem(R.string.subtitle_add_file, Icons.Default.Add, checked = false) { subtitlePicker.launch(SUBTITLE_MIMES) },
        PlayerMenuItem(R.string.menu_settings, Icons.Default.Settings, checked = false) { currentOpenSettings() },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.letterbox)
            .onGloballyPositioned { registry.attachSurface(it) },
    ) {
        val landscape = maxWidth > maxHeight
        // In portrait the subtitle list sits below the picture; the picture and its chrome shrink to fit.
        val listBelow = ui.list.open && !landscape && !inPip
        val regionWidth = maxWidth
        val regionHeight = if (listBelow) maxHeight * (1f - Dimens.listPortraitShare) else maxHeight
        val chromeShown = (ui.controlsVisible || ui.playback.isEnded || ui.playback.error != null) &&
            !ui.layoutMode && !ui.locked && !inPip
        val dockShown = !inPip && !ui.layoutMode && !ui.locked &&
            (chromeShown || !ui.settings.quickActions.autoHideWithControls)

        Box(
            modifier = (if (listBelow) {
                Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(1f - Dimens.listPortraitShare)
            } else {
                Modifier.fillMaxSize()
            }),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        setUseController(false)
                        setBackgroundColor(colors.letterbox.toArgb())
                        subtitleView?.visibility = View.GONE
                        viewRef[0] = this
                        viewModel.controller.attachView(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (ui.settings.player.dimOverlayPercent > 0 && ui.controlsVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.playerScrim.copy(alpha = ui.settings.player.dimOverlayPercent / PERCENT)),
                )
            }

            SubtitleLayerStack(ui = ui, registry = registry, modifier = Modifier.fillMaxSize())

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestures(gestureMode, registry, viewModel, tuning, readLevel),
            )

            if (dockShown) {
                QuickActionDock(
                    ui = ui,
                    areaWidth = regionWidth,
                    areaHeight = regionHeight,
                    onTap = viewModel::onQuickTap,
                    onHoldStart = viewModel::onQuickHoldStart,
                    onHoldEnd = viewModel::onQuickHoldEnd,
                    onMoveFloating = viewModel::moveQuickAction,
                    registry = registry,
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }

            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ChromeVisibility(visible = chromeShown, modifier = Modifier.align(Alignment.TopCenter)) {
                    TopChrome(
                        ui = ui,
                        onBack = { handleBackFrom(ui, viewModel, onBack) },
                        onSheet = viewModel::setSheet,
                        onMenuOpenChange = viewModel::setMenuOpen,
                        menuActions = menuActions,
                        registry = registry,
                    )
                }

                ChromeVisibility(visible = chromeShown, modifier = Modifier.align(Alignment.Center)) {
                    CenterCluster(
                        playing = ui.playback.isPlaying,
                        buffering = ui.playback.isBuffering,
                        seekStepMs = ui.settings.player.seekStepMs,
                        onTogglePlay = viewModel::togglePlay,
                        onSeekBy = viewModel::seekBy,
                        registry = registry,
                    )
                }

                ChromeVisibility(visible = chromeShown, modifier = Modifier.align(Alignment.BottomCenter)) {
                    BottomChrome(
                        ui = ui,
                        onScrubStart = viewModel::onScrubStart,
                        onScrubEnd = viewModel::onScrubEnd,
                        onPreviousBlock = { viewModel.stepBlock(-1) },
                        onNextBlock = { viewModel.stepBlock(1) },
                        onRepeatBlock = { viewModel.toggleRepeatBlock(autoRepeat = false) },
                        onRepeatBlockHold = { viewModel.toggleRepeatBlock(autoRepeat = true) },
                        onSpeed = { viewModel.setSheet(Sheet.SPEED) },
                        onAspect = { viewModel.setSheet(Sheet.ASPECT) },
                        onPlaylist = { viewModel.setSheet(Sheet.PLAYLIST) },
                        onToggleList = viewModel::toggleList,
                        onListLongPress = viewModel::toggleNoSpoiler,
                        registry = registry,
                    )
                }

                val lockShown = if (ui.locked) ui.lockButtonVisible else chromeShown
                ChromeVisibility(
                    visible = lockShown,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = Dimens.playerEdgeInset),
                ) {
                    LockButton(locked = ui.locked, onClick = viewModel::toggleLock, registry = registry)
                }

                if (ui.layoutMode) {
                    LayoutModeBanner(
                        hint = stringResource(R.string.subtitle_layout_hint),
                        onDone = { viewModel.setLayoutMode(false) },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = Dimens.xl),
                    )
                }
            }

            GestureHudLayer(hud = ui.hud, modifier = Modifier.fillMaxSize())

            if (ui.playback.isBuffering && ui.settings.player.showBufferIndicator && ui.playback.error == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(Dimens.iconButtonLarge),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = Dimens.spinnerStroke,
                )
            }

            if (ui.opening) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.playerScrim),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.iconButtonLarge),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = Dimens.spinnerStroke,
                    )
                }
            }

            if (ui.playback.target == null && !ui.opening && ui.playback.error == null) {
                Text(
                    text = stringResource(R.string.player_no_media),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.controlIdle,
                    modifier = Modifier.align(Alignment.Center).padding(Dimens.lg),
                )
            }

            ui.playback.error?.let { error ->
                ErrorCard(
                    error = error,
                    onRetry = { start?.let { viewModel.open(it.uri, it.title, it.subtitles) } },
                    onSwitchDecoder = { viewModel.setDecoder(DecoderMode.SOFTWARE) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            ui.message?.let { text ->
                Snackbarish(
                    text = text,
                    onDismiss = { viewModel.message(null) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.snackbarBottom),
                )
            }
        }

        if (!inPip) {
            SubtitleListPanel(
                list = ui.list,
                reduceMotion = reduceMotion,
                onToggle = viewModel::toggleList,
                onRoleChange = viewModel::setListRole,
                onQuery = viewModel::setListQuery,
                onSeekToRow = viewModel::seekToRow,
                onToggleSpoiler = viewModel::toggleNoSpoiler,
                modifier = Modifier.fillMaxSize(),
                landscape = landscape,
                registry = registry,
            )
        }

        PopupLayer(
            popup = ui.popup,
            settings = ui.settings,
            onDismiss = viewModel::dismissPopup,
            onToggleMark = viewModel::toggleMarkInPopup,
            onDownloadModel = viewModel::downloadModel,
            onAskAi = { viewModel.askAi(it) },
            modifier = Modifier.fillMaxSize(),
            registry = registry,
        )

        if (ui.ai.state != AiState.IDLE && !inPip) {
            AiAnswerSheet(
                ui = ui,
                onDismiss = viewModel::cancelAi,
                onAskAgain = { viewModel.askAi(ui.ai.question) },
                onResume = viewModel::resumeAfterAi,
                onCopy = { text -> context.copyToClipboard(text) },
                onOpenSettings = currentOpenSettings,
            )
        }

        SheetsHost(
            ui = ui,
            viewModel = viewModel,
            onDismiss = { viewModel.setSheet(Sheet.NONE) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The back action, shared by the top bar button and the system back gesture. */
private fun handleBackFrom(ui: PlayerUi, viewModel: PlayerViewModel, onBack: () -> Unit) {
    when {
        ui.ai.state != AiState.IDLE -> viewModel.cancelAi()
        ui.sheet != Sheet.NONE -> viewModel.setSheet(Sheet.NONE)
        ui.menuOpen -> viewModel.setMenuOpen(false)
        ui.list.open -> viewModel.toggleList()
        ui.layoutMode -> viewModel.setLayoutMode(false)
        ui.locked -> viewModel.onSurfaceTap()
        else -> onBack()
    }
}

/** What the player was opened with; kept in :app's navigation and passed straight down. */
data class PlayerStart(val uri: String, val title: String, val subtitles: List<PlayerViewModel.PickedSubtitle> = emptyList())

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit, onSwitchDecoder: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(Dimens.lg),
        shape = RoundedCornerShape(Dimens.radiusLg),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(Dimens.lg)) {
            Text(stringResource(R.string.player_error, error), color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(Dimens.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_apply)) }
                TextButton(onClick = onSwitchDecoder) { Text(stringResource(R.string.player_decoder_software)) }
            }
        }
    }
}

/** Reads the current level for a gesture's starting point, so a drag continues from what is on screen. */
private fun levelReader(context: Context, activity: Activity?): (GestureAction) -> Float = { action ->
    when (action) {
        GestureAction.BRIGHTNESS -> activity?.currentBrightness() ?: DEFAULT_BRIGHTNESS
        GestureAction.VOLUME -> context.currentVolumeFraction()
        else -> 0f
    }
}

private fun Activity.currentBrightness(): Float {
    val explicit = window.attributes.screenBrightness
    if (explicit >= 0f) return explicit
    val system = runCatching {
        SystemSettings.System.getInt(contentResolver, SystemSettings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(DEFAULT_SYSTEM_BRIGHTNESS)
    return system / SYSTEM_BRIGHTNESS_MAX
}

private fun Context.currentVolumeFraction(): Float {
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0f
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun orientationFor(lock: OrientationLock): Int = when (lock) {
    OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    OrientationLock.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    OrientationLock.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

// ---------------------------------------------------------------- window helpers

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        (ctx as? Activity)?.let { return it }
        ctx = ctx.baseContext
    }
    return null
}

private fun Activity.setScreenBrightness(value: Float?) {
    window.attributes = window.attributes.apply {
        screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun Activity.setSystemVolume(fraction: Float) {
    val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    manager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).toInt().coerceIn(0, max), 0)
}

private fun PlayerIntent.Share.toShareIntent(): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, uri)
    putExtra(Intent.EXTRA_SUBJECT, title)
}

private fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("SubLearn", text))
}

/** Picture-in-picture with the video's own aspect ratio, so the window never letterboxes. */
internal fun Activity.enterPipMode(aspectWidth: Int, aspectHeight: Int) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
    val params = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(Rational(aspectWidth.coerceAtLeast(1), aspectHeight.coerceAtLeast(1)))
        .build()
    runCatching { enterPictureInPictureMode(params) }
}

private val SUBTITLE_MIMES = arrayOf("text/*", "application/octet-stream", "application/x-subrip")

/** Percent to fraction for the dim overlay. */
private const val PERCENT = 100f

/** Brightness assumed when the window has no override and the system value cannot be read. */
private const val DEFAULT_BRIGHTNESS = 0.5f

/** The system brightness setting runs from 0 to 255. */
private const val SYSTEM_BRIGHTNESS_MAX = 255f
private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
