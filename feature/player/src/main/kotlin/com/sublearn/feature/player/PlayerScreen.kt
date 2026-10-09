package com.sublearn.feature.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.R
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.subtitles.TrackRole

/**
 * The player: a Media3 surface with SubLearn's own subtitle layers on top (PLY-1..PLY-10).
 *
 * The surface is [PlayerView] because that is what Media3 optimises for (aspect handling, shutter,
 * decoder surface), while everything the user reads or touches is Compose. Window-level work stays
 * in the [PlayerIntent] stream, so the composable never reaches for an Activity directly.
 */
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
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }
    val subtitlePicker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("text/*", "application/octet-stream"))) { picked ->
        if (picked != null) {
            val role = SubtitleRoleGuess
                .guess(picked.name, ui.settings.languages.nativeLanguage)
                .role
            viewModel.loadFile(role, picked.uri, picked.name)
        }
    }

    LaunchedEffect(start?.uri) {
        start?.let { viewModel.open(it.uri, it.title, it.subtitles) }
    }

    // Window effects: system bars, orientation, brightness, keep-screen-on, PiP.
    LaunchedEffect(ui.locked, ui.layoutMode) {
        val hide = ui.locked || ui.layoutMode
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, window.decorView).apply {
                val bars = WindowInsetsCompat.Type.systemBars()
                if (hide) hide(bars) else show(bars)
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
    LaunchedEffect(ui.settings.player.orientationLock) {
        activity?.requestedOrientation = when (ui.settings.player.orientationLock) {
            OrientationLock.AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.REVERSE_LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            OrientationLock.SENSOR -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    LaunchedEffect(ui.settings.player.keepScreenOn) {
        val keep = ui.settings.player.keepScreenOn
        activity?.window?.let { window ->
            val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (keep) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.intents.collect { intent ->
            when (intent) {
                is PlayerIntent.Brightness -> activity?.setScreenBrightness(intent.value)
                is PlayerIntent.Volume -> activity?.setSystemVolume(intent.value)
                is PlayerIntent.EnterPip -> activity?.enterPipMode(intent.aspectWidth, intent.aspectHeight)
                is PlayerIntent.HideSystemBars -> Unit
                is PlayerIntent.Orientation -> Unit
                is PlayerIntent.Share -> context.startActivity(Intent.createChooser(intent.toShareIntent(), intent.title))
                is PlayerIntent.OpenWords -> onOpenWords()
                is PlayerIntent.OpenSettings -> onOpenSettings()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.onLeave()
            viewRef[0]?.let { viewModel.controller.detachView(it) }
            activity?.setScreenBrightness(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (ui.layoutMode) Modifier.imePadding() else Modifier)
    ) {
        val reduceMotion = LocalReduceMotion.current

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also {
                    it.setBackgroundColor(android.graphics.Color.BLACK)
                    viewRef[0] = it
                    viewModel.controller.attachView(it)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (ui.settings.player.dimOverlayPercent > 0 && ui.controlsVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = ui.settings.player.dimOverlayPercent / 100f)),
            )
        }

        // Subtitle layers sit above the video and below the chrome (SUB-1).
        SubtitleLayerStack(
            ui = ui,
            onLayerTap = viewModel::onLayerTap,
            onMove = viewModel::moveLayer,
            modifier = Modifier.fillMaxSize(),
        )

        if (!ui.locked) {
            GestureLayer(
                settings = ui.settings,
                enabled = !ui.layoutMode && !ui.locked,
                onAction = viewModel::onAction,
                onDrag = viewModel::onDrag,
                onDragEnd = { viewModel.showControls(400L) },
                onDoubleTap = { side -> viewModel.onDoubleTap(side) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        PopupLayer(
            popup = ui.popup,
            settings = ui.settings,
            onDismiss = viewModel::dismissPopup,
            onToggleMark = viewModel::toggleMarkInPopup,
            onDownloadModel = viewModel::downloadModel,
            onAskAi = { viewModel.askAi(it) },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        ChromeVisibility(
            visible = (ui.controlsVisible || ui.playback.isEnded || ui.playback.error != null) && !ui.layoutMode && !inPip,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                ui = ui,
                onBack = {
                    viewModel.onLeave()
                    onBack()
                },
                onToggleLock = viewModel::toggleLock,
                onOpenSheet = viewModel::setSheet,
                onPip = viewModel::enterPip,
            )
        }

        ChromeVisibility(
            visible = (ui.controlsVisible || !ui.playback.isPlaying) && !ui.layoutMode && !inPip,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column {
                QuickActionBar(
                    ui = ui,
                    onAction = { id, inverted -> viewModel.onQuickAction(id, inverted) },
                    onAddSubtitleFile = { subtitlePicker.launch(SUBTITLE_MIMES) },
                    onMoveFloating = { id, x, y ->
                        viewModel.updateQuickAction(id) { spec -> spec.copy(xFraction = x, yFraction = y) }
                    },
                )
                BottomControls(
                    ui = ui,
                    onSeek = viewModel::seekBy,
                    onTogglePlay = viewModel::togglePlay,
                    onStepBlock = viewModel::stepBlock,
                    onToggleRepeat = viewModel::toggleRepeatBlock,
                    onToggleStop = viewModel::toggleStopAtEnd,
                    onToggleList = viewModel::toggleList,
                    onAskAi = { viewModel.askAi(null) },
                    modifier = Modifier.padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                )
            }
        }

        if (ui.playback.isBuffering && ui.settings.player.showBufferIndicator) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(38.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }

        ui.playback.error?.let { error ->
            ErrorCard(
                error = error,
                onRetry = { start?.let { it2 -> viewModel.open(it2.uri, it2.title, it2.subtitles) } },
                onSwitchDecoder = { viewModel.setDecoder(com.sublearn.core.settings.DecoderMode.SOFTWARE) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        ui.message?.let { text ->
            Snackbarish(
                text = text,
                onDismiss = { viewModel.message(null) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }

        if (ui.opening) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        if (ui.layoutMode) {
            LayoutModeBanner(
                hint = stringResource(R.string.subtitle_layout_hint),
                onDone = { viewModel.setLayoutMode(false) },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        }

        if (ui.ai.state != AiState.IDLE && !inPip) {
            AiAnswerSheet(
                ui = ui,
                onDismiss = viewModel::cancelAi,
                onAskAgain = { viewModel.askAi(ui.ai.question) },
                onResume = viewModel::resumeAfterAi,
                onCopy = { text -> context.copyToClipboard(text) },
                onOpenSettings = onOpenSettings,
            )
        }

        SheetsHost(
            ui = ui,
            viewModel = viewModel,
            onDismiss = { viewModel.setSheet(Sheet.NONE) },
            modifier = Modifier.fillMaxSize(),
        )

        SubtitleListPanel(
            list = ui.list,
            reduceMotion = reduceMotion,
            onToggle = viewModel::toggleList,
            onRoleChange = viewModel::setListRole,
            onQuery = viewModel::setListQuery,
            onSeekToRow = viewModel::seekToRow,
            onToggleSpoiler = viewModel::toggleNoSpoiler,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** What the player was opened with; kept in :app's navigation and passed straight down. */
data class PlayerStart(val uri: String, val title: String, val subtitles: List<PlayerViewModel.PickedSubtitle> = emptyList())

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit, onSwitchDecoder: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.padding(24.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.player_error, error), color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(R.string.action_apply)) }
                androidx.compose.material3.TextButton(onClick = onSwitchDecoder) { Text(stringResource(R.string.player_decoder_software)) }
            }
        }
    }
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
    val manager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
    val max = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (fraction * max).toInt().coerceIn(0, max), 0)
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

fun Activity.enterPipMode(aspectWidth: Int, aspectHeight: Int) {
    if (android.os.Build.VERSION.SDK_INT < 26) return
    val params = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational(aspectWidth.coerceAtLeast(1), aspectHeight.coerceAtLeast(1)))
        .build()
    runCatching { enterPictureInPictureMode(params) }
}

private val SUBTITLE_MIMES = arrayOf("text/*", "application/octet-stream", "application/x-subrip")

