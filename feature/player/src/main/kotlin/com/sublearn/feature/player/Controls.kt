package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.R
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.subtitles.TrackRole

/**
 * The player chrome (PLY-1, PLY-3, PLY-11), MX-style: a toolbar that can disappear, one seek line
 * that is a line and not a slider, one transport row, and the action bar in [ActionBar].
 *
 * Every piece fades in and out through [ChromeVisibility], so the picture is never boxed in by a
 * panel that cannot leave. Sizes come from [Dimens] and colours from [LocalSubLearnColors]; nothing
 * here invents a value.
 */

/**
 * Fades and slides the chrome in and out. Timing and the reduce-motion variant come from
 * [Motion]; this file never picks a duration of its own.
 */
@Composable
internal fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = Motion.controlsEnter(reduce),
        exit = Motion.controlsExit(reduce),
        modifier = modifier,
    ) { content() }
}

/**
 * A 44 dp target around one icon. Only a few controls have a second meaning on long press, so
 * [onLongClick] is optional and the default is a plain tap.
 */
@Composable
internal fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalSubLearnColors.current
    val tint = when {
        !enabled -> colors.controlDisabled
        selected -> colors.controlActive
        else -> colors.controlIdle
    }
    Box(
        modifier = modifier
            .size(Dimens.touchTarget)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

/**
 * The shaded band behind the top and bottom chrome. It fades to nothing so the picture is never
 * boxed in; the scrim colour is a design-system role, so both themes stay readable.
 */
@Composable
private fun chromeBrush(towardsBottom: Boolean): Brush {
    val scrim = LocalSubLearnColors.current.playerScrim
    val clear = scrim.copy(alpha = 0f)
    return if (towardsBottom) {
        Brush.verticalGradient(listOf(clear, scrim))
    } else {
        Brush.verticalGradient(listOf(scrim, clear))
    }
}

/**
 * PLY-1: back and the title on the left; audio, subtitles, rotation, the decoder and More on the
 * right. Audio and subtitles open their own sheets (PLY-8, PLY-13), never one combined list.
 */
@Composable
internal fun TopChrome(
    ui: PlayerUi,
    onBack: () -> Unit,
    onSheet: (Sheet) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onOrientationLock: (OrientationLock) -> Unit,
    onRotateQuarterTurn: () -> Unit,
    menuActions: List<PlayerMenuItem>,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSubLearnColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .blocksGestures(registry)
            .background(chromeBrush(towardsBottom = false))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = Dimens.playerEdgeInset, vertical = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.player_back),
            onClick = onBack,
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = Dimens.sm)) {
            Text(
                text = ui.playback.target?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.controlActive,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ui.playback.hasVideo) {
                Text(
                    text = "${ui.playback.videoWidth} × ${ui.playback.videoHeight}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.controlIdle,
                    maxLines = 1,
                )
            }
        }
        ChromeIconButton(
            icon = Icons.Default.GraphicEq,
            contentDescription = stringResource(R.string.player_audio_manager),
            onClick = { onSheet(Sheet.AUDIO) },
            selected = ui.muted,
        )
        ChromeIconButton(
            icon = Icons.Default.ClosedCaption,
            contentDescription = stringResource(R.string.player_subtitle_manager),
            onClick = { onSheet(Sheet.SUBTITLES) },
        )
        RotationMenu(
            lock = ui.settings.player.orientationLock,
            onSelect = onOrientationLock,
            onRotate = onRotateQuarterTurn,
        )
        TextButton(onClick = { onSheet(Sheet.DECODER) }, modifier = Modifier.height(Dimens.touchTarget)) {
            Text(
                text = ui.settings.player.decoder.shortLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = colors.controlActive,
            )
        }
        Box {
            ChromeIconButton(
                icon = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.player_more),
                onClick = { onMenuOpenChange(true) },
            )
            DropdownMenu(
                expanded = ui.menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                menuActions.forEach { entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.label)) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        trailingIcon = if (entry.checked) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon)) }
                        } else {
                            null
                        },
                        onClick = {
                            onMenuOpenChange(false)
                            entry.onClick()
                        },
                    )
                }
            }
        }
    }
}

/**
 * PLY-5: the rotation button. Tapping it turns the picture a quarter turn; holding it opens the
 * direction list, which is where the sensor option lives next to the fixed ones.
 */
@Composable
private fun RotationMenu(
    lock: OrientationLock,
    onSelect: (OrientationLock) -> Unit,
    onRotate: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(
            icon = Icons.Default.ScreenRotation,
            contentDescription = stringResource(R.string.player_rotate),
            onClick = onRotate,
            onLongClick = { open = true },
            selected = lock != OrientationLock.AUTO,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OrientationLock.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.playerLabelRes())) },
                    leadingIcon = if (entry == lock) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

/** One row of the overflow menu. Built by the screen so the menu lists every action in one place. */
data class PlayerMenuItem(
    val label: Int,
    val icon: ImageVector,
    val checked: Boolean,
    val onClick: () -> Unit,
)

/**
 * PLY-3 bottom, in two rows and never more: the seek line with the two time labels on it, and the
 * transport row.
 *
 * The time labels swap between elapsed/total and elapsed/time-left (PLY-10) when either of them is
 * tapped, so the row never grows a third label.
 */
@Composable
internal fun BottomChrome(
    ui: PlayerUi,
    onScrubStart: (Long) -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onToggleRemainingTime: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPreviousVideo: () -> Unit,
    onNextVideo: () -> Unit,
    onPreviousBlock: () -> Unit,
    onNextBlock: () -> Unit,
    onRepeatBlock: () -> Unit,
    onRepeatBlockHold: () -> Unit,
    onSpeed: () -> Unit,
    onAspect: () -> Unit,
    onPlaylist: () -> Unit,
    onToggleList: () -> Unit,
    onListLongPress: () -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSubLearnColors.current
    val state = ui.playback
    Column(
        modifier = modifier
            .fillMaxWidth()
            .blocksGestures(registry)
            .background(chromeBrush(towardsBottom = true))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = Dimens.playerEdgeInset),
    ) {
        SeekRow(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            bufferedFraction = state.bufferedFraction,
            live = state.isLive || state.durationMs <= 0L,
            remaining = ui.settings.player.showRemainingTime,
            onToggleRemainingTime = onToggleRemainingTime,
            onScrubStart = onScrubStart,
            onScrubUpdate = onScrubUpdate,
            onScrubEnd = onScrubEnd,
        )
        ui.shadowing.segmentLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.controlActive,
                modifier = Modifier.padding(start = Dimens.xs),
            )
        }
        TransportRow(
            ui = ui,
            onPlayPause = onPlayPause,
            onSeekBy = onSeekBy,
            onPreviousVideo = onPreviousVideo,
            onNextVideo = onNextVideo,
            onPreviousBlock = onPreviousBlock,
            onNextBlock = onNextBlock,
            onRepeatBlock = onRepeatBlock,
            onRepeatBlockHold = onRepeatBlockHold,
            onSpeed = onSpeed,
            onAspect = onAspect,
            onPlaylist = onPlaylist,
            onToggleList = onToggleList,
            onListLongPress = onListLongPress,
        )
    }
}

/**
 * PLY-10: elapsed on the left, total (or time left) on the right, and one hairline between them.
 * The line is drawn by hand: a Material slider would bring a thumb, a halo and a second row with it.
 */
@Composable
private fun SeekRow(
    positionMs: Long,
    durationMs: Long,
    bufferedFraction: Float,
    live: Boolean,
    remaining: Boolean,
    onToggleRemainingTime: () -> Unit,
    onScrubStart: (Long) -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
) {
    var draft by remember { mutableFloatStateOf(NOT_SCRUBBING) }
    val scrubbing = draft >= 0f
    val fraction = if (scrubbing) draft else if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val elapsedMs = if (scrubbing) (fraction * durationMs).toLong() else positionMs
    val tailMs = if (remaining) (durationMs - elapsedMs).coerceAtLeast(0L) else durationMs
    val timeLabelToggle = stringResource(R.string.player_time_remaining)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeLabel(
            text = clock(elapsedMs),
            onClickLabel = timeLabelToggle,
            onClick = onToggleRemainingTime,
            modifier = Modifier.padding(start = Dimens.xs),
        )
        SeekLine(
            fraction = fraction,
            bufferedFraction = bufferedFraction,
            scrubbing = scrubbing,
            live = live,
            onFractionChange = { next ->
                if (draft < 0f) onScrubStart((next * durationMs).toLong())
                draft = next
                onScrubUpdate((next * durationMs).toLong())
            },
            onFractionEnd = { next ->
                onScrubEnd((next * durationMs).toLong())
                draft = NOT_SCRUBBING
            },
            modifier = Modifier.weight(1f).padding(horizontal = Dimens.sm),
        )
        TimeLabel(
            text = if (live) stringResource(R.string.player_live) else clock(tailMs),
            onClickLabel = timeLabelToggle,
            onClick = onToggleRemainingTime,
            modifier = Modifier.padding(end = Dimens.xs),
        )
    }
}

/**
 * One time label. Tapping either of them swaps the right-hand end between total and time left, and
 * the accessibility label says what the tap will do.
 */
@Composable
private fun TimeLabel(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = Dimens.iconButtonLarge)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(vertical = Dimens.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = LocalSubLearnColors.current.controlActive,
            maxLines = 1,
        )
    }
}

/**
 * The seek line: a 3 dp track, the buffered range behind it, a filled part up to the playhead and an
 * 8 dp dot (12 dp while dragging). Touching anywhere on it starts a scrub, moving it keeps the
 * picture with the finger (PLY-14) and lifting it commits.
 */
@Composable
private fun SeekLine(
    fraction: Float,
    bufferedFraction: Float,
    scrubbing: Boolean,
    live: Boolean,
    onFractionChange: (Float) -> Unit,
    onFractionEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSubLearnColors.current
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    val currentChange by rememberUpdatedState(onFractionChange)
    val currentEnd by rememberUpdatedState(onFractionEnd)
    Canvas(
        modifier = modifier
            .height(Dimens.touchTarget)
            .onSizeChanged { size -> widthPx = size.width.toFloat() }
            .pointerInput(live) {
                if (live) return@pointerInput
                // The width is read when the finger arrives, not when the modifier is built: the
                // first composition happens before the first layout pass, so it would still be zero.
                fun at(x: Float): Float = (x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var target = at(down.position.x)
                    currentChange(target)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            target = at(change.position.x)
                            change.consume()
                            break
                        }
                        val next = at(change.position.x)
                        if (next != target) {
                            target = next
                            currentChange(target)
                        }
                        change.consume()
                    }
                    currentEnd(target)
                }
            },
    ) {
        val trackHeight = with(density) { Dimens.seekLine.toPx() }
        val dotRadius = with(density) { (if (scrubbing) Dimens.seekDotActive else Dimens.seekDot).toPx() } / 2f
        val centerY = size.height / 2f
        val lineWidth = size.width
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = colors.controlDisabled,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = androidx.compose.ui.geometry.Size(lineWidth, trackHeight),
            cornerRadius = radius,
        )
        if (bufferedFraction > 0f) {
            drawRoundRect(
                color = colors.controlIdle.copy(alpha = BUFFERED_ALPHA),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = androidx.compose.ui.geometry.Size(lineWidth * bufferedFraction.coerceIn(0f, 1f), trackHeight),
                cornerRadius = radius,
            )
        }
        if (fraction > 0f) {
            drawRoundRect(
                color = colors.controlActive,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = androidx.compose.ui.geometry.Size(lineWidth * fraction.coerceIn(0f, 1f), trackHeight),
                cornerRadius = radius,
            )
        }
        drawCircle(
            color = colors.controlActive,
            radius = dotRadius,
            center = Offset(lineWidth * fraction.coerceIn(0f, 1f), centerY),
        )
    }
}

/**
 * PLY-3: the transport row. It is a single row, so it only scrolls when the buttons genuinely do
 * not fit; when they do, the group stays centred and the row does not move at all.
 *
 * previous/next video appear only when a playlist has more than one item, because a button that
 * cannot go anywhere is worse than no button; the cue step and the 10 s steps are always there.
 */
@Composable
private fun TransportRow(
    ui: PlayerUi,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPreviousVideo: () -> Unit,
    onNextVideo: () -> Unit,
    onPreviousBlock: () -> Unit,
    onNextBlock: () -> Unit,
    onRepeatBlock: () -> Unit,
    onRepeatBlockHold: () -> Unit,
    onSpeed: () -> Unit,
    onAspect: () -> Unit,
    onPlaylist: () -> Unit,
    onToggleList: () -> Unit,
    onListLongPress: () -> Unit,
) {
    val colors = LocalSubLearnColors.current
    val state = ui.playback
    val step = ui.settings.player.seekStepMs
    val inPlaylist = state.playlist.size > 1
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        val viewport = maxWidth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
        ) {
            Row(
                modifier = Modifier.widthIn(min = viewport),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inPlaylist) {
                    ChromeIconButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous_video),
                        onClick = onPreviousVideo,
                    )
                }
                ChromeIconButton(
                    icon = Icons.Default.Replay10,
                    contentDescription = stringResource(R.string.player_seek_backward),
                    onClick = { onSeekBy(-step) },
                    enabled = !state.isLive,
                )
                ChromeIconButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous_subtitle),
                    onClick = onPreviousBlock,
                    enabled = ui.canStepBySubtitle,
                )
                PlayPauseButton(
                    playing = state.isPlaying,
                    buffering = state.isBuffering,
                    onClick = onPlayPause,
                )
                ChromeIconButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.player_next_subtitle),
                    onClick = onNextBlock,
                    enabled = ui.canStepBySubtitle,
                )
                ChromeIconButton(
                    icon = Icons.Default.Forward10,
                    contentDescription = stringResource(R.string.player_seek_forward),
                    onClick = { onSeekBy(step) },
                    enabled = !state.isLive,
                )
                if (inPlaylist) {
                    ChromeIconButton(
                        icon = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.player_next_video),
                        onClick = onNextVideo,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = Dimens.sm)
                        .size(width = Dimens.hairline, height = Dimens.xl)
                        .background(colors.controlDisabled),
                )
                ChromeIconButton(
                    icon = Icons.Default.Repeat,
                    contentDescription = stringResource(R.string.player_repeat_subtitle),
                    onClick = onRepeatBlock,
                    onLongClick = onRepeatBlockHold,
                    selected = ui.shadowing.active,
                    enabled = ui.canStepBySubtitle,
                )
                TextButton(onClick = onSpeed, modifier = Modifier.height(Dimens.touchTarget)) {
                    Text(
                        text = stringResource(R.string.player_speed_value, state.speedPercent),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.speedPercent != NORMAL_SPEED_PERCENT) colors.controlActive else colors.controlIdle,
                    )
                }
                ChromeIconButton(
                    icon = Icons.Default.AspectRatio,
                    contentDescription = stringResource(R.string.player_aspect),
                    onClick = onAspect,
                )
                if (state.playlist.size > 1) {
                    ChromeIconButton(
                        icon = Icons.Default.PlaylistPlay,
                        contentDescription = stringResource(R.string.player_playlist),
                        onClick = onPlaylist,
                    )
                }
                ChromeIconButton(
                    icon = Icons.Default.List,
                    contentDescription = stringResource(R.string.subtitle_list),
                    onClick = onToggleList,
                    selected = ui.list.open,
                    onLongClick = onListLongPress,
                )
            }
        }
    }
}

/** The one round button of the row: play, or pause, with the buffering spinner inside it. */
@Composable
private fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    val colors = LocalSubLearnColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.size(Dimens.iconButtonLarge),
        shape = CircleShape,
        color = colors.strongBackdrop,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconButtonLarge),
                    strokeWidth = Dimens.spinnerStroke,
                    color = colors.controlActive,
                )
            }
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                tint = colors.controlActive,
                modifier = Modifier.size(Dimens.xl),
            )
        }
    }
}

/** The speed the chip reads as "normal" (no highlight). */
private const val NORMAL_SPEED_PERCENT = 100

/** Marks "not dragging" in the seek row's fraction state. */
private const val NOT_SCRUBBING = -1f

/** The buffered range is a hint, not a value, so it stays quiet under the playhead. */
private const val BUFFERED_ALPHA = 0.35f

/** PLY-1 corner lock. While locked it shows the unlock icon; the screen decides when it is visible. */
@Composable
internal fun LockButton(
    locked: Boolean,
    onClick: () -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.blocksGestures(registry),
        shape = CircleShape,
        color = LocalSubLearnColors.current.strongBackdrop,
    ) {
        ChromeIconButton(
            icon = if (locked) Icons.Default.LockOpen else Icons.Default.Lock,
            contentDescription = stringResource(if (locked) R.string.player_unlock else R.string.player_lock),
            onClick = onClick,
        )
    }
}

/** Shown during an arrangement: the one instruction the user needs, and a way out. */
@Composable
internal fun LayoutModeBanner(hint: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSubLearnColors.current
    Surface(
        modifier = modifier
            .widthIn(max = Dimens.bannerMaxWidth)
            .padding(horizontal = Dimens.lg),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = colors.infoSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = Dimens.lg, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.infoText,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone, modifier = Modifier.height(Dimens.touchTarget)) {
                Text(stringResource(R.string.action_ok), color = colors.infoText)
            }
        }
    }
}

/**
 * A short status line; tapping it dismisses it early. The timing lives in the ViewModel.
 *
 * It sits on [com.sublearn.core.designsystem.SubLearnColors.infoSurface] with
 * [com.sublearn.core.designsystem.SubLearnColors.infoText] ink, because the chrome underneath is
 * dark in both themes and the theme's own on-surface colour is not (GEN-1).
 */
@Composable
internal fun Snackbarish(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSubLearnColors.current
    Surface(
        modifier = modifier
            .widthIn(max = Dimens.bannerMaxWidth)
            .padding(horizontal = Dimens.lg)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .combinedClickable(onClick = onDismiss),
        color = colors.infoSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.infoText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.sm),
        )
    }
}

/** Whether a quick action currently reads as "on", so its icon takes the active tint. */
internal fun quickSelected(ui: PlayerUi, id: QuickActionId): Boolean = when (id) {
    QuickActionId.TOGGLE_LEARNING -> ui.layer(TrackRole.LEARNING).visible
    QuickActionId.TOGGLE_TRANSLATION -> ui.layer(TrackRole.TRANSLATION).visible
    QuickActionId.REPEAT_BLOCK -> ui.shadowing.active
    QuickActionId.STOP_AT_BLOCK_END -> ui.shadowing.stopAtEnd
    QuickActionId.LAYOUT_MODE -> ui.layoutMode
    QuickActionId.SUBTITLE_LIST -> ui.list.open
    QuickActionId.ROTATE -> ui.settings.player.orientationLock != OrientationLock.AUTO
    else -> false
}

/** Icon for each quick action. The mapping is one-to-one so a new id cannot ship without an icon. */
internal fun QuickActionId.icon(): ImageVector = when (this) {
    QuickActionId.TOGGLE_LEARNING -> Icons.Default.School
    QuickActionId.TOGGLE_TRANSLATION -> Icons.Default.Translate
    QuickActionId.REPEAT_BLOCK -> Icons.Default.Repeat
    QuickActionId.STOP_AT_BLOCK_END -> Icons.Default.StopCircle
    QuickActionId.LAYOUT_MODE -> Icons.Default.DragIndicator
    QuickActionId.SUBTITLE_LIST -> Icons.Default.List
    QuickActionId.AI_EXPLAIN -> Icons.Default.AutoAwesome
    QuickActionId.MY_WORDS -> Icons.Default.Star
    QuickActionId.PLAYLIST -> Icons.Default.PlaylistPlay
    QuickActionId.ASPECT_RATIO -> Icons.Default.Fullscreen
    QuickActionId.DECODER -> Icons.Default.Memory
    QuickActionId.AUDIO_TRACK -> Icons.Default.GraphicEq
    QuickActionId.SUBTITLE_TRACKS -> Icons.Default.Subtitles
    QuickActionId.SUBTITLE_TOOLS -> Icons.Default.Tune
    QuickActionId.SPEED -> Icons.Default.Speed
    QuickActionId.PICTURE_IN_PICTURE -> Icons.Default.PictureInPictureAlt
    QuickActionId.SHARE -> Icons.Default.Share
    QuickActionId.SETTINGS -> Icons.Default.Settings
    QuickActionId.ROTATE -> Icons.Default.ScreenRotation
}

/** Accessible name for each quick action. */
internal fun QuickActionId.labelRes(): Int = when (this) {
    QuickActionId.TOGGLE_LEARNING -> R.string.subtitle_layer_learning
    QuickActionId.TOGGLE_TRANSLATION -> R.string.subtitle_layer_translation
    QuickActionId.REPEAT_BLOCK -> R.string.player_repeat_subtitle
    QuickActionId.STOP_AT_BLOCK_END -> R.string.player_stop_at_end
    QuickActionId.LAYOUT_MODE -> R.string.subtitle_layout_mode
    QuickActionId.SUBTITLE_LIST -> R.string.subtitle_list
    QuickActionId.AI_EXPLAIN -> R.string.ai_explain
    QuickActionId.MY_WORDS -> R.string.words_title
    QuickActionId.PLAYLIST -> R.string.player_playlist
    QuickActionId.ASPECT_RATIO -> R.string.player_aspect
    QuickActionId.DECODER -> R.string.player_decoder
    QuickActionId.AUDIO_TRACK -> R.string.player_audio_manager
    QuickActionId.SUBTITLE_TRACKS -> R.string.player_subtitle_manager
    QuickActionId.SUBTITLE_TOOLS -> R.string.subtitle_tools
    QuickActionId.SPEED -> R.string.player_speed
    QuickActionId.PICTURE_IN_PICTURE -> R.string.player_pip
    QuickActionId.SHARE -> R.string.player_share
    QuickActionId.SETTINGS -> R.string.menu_settings
    QuickActionId.ROTATE -> R.string.player_rotate
}

/**
 * Names of the orientation choices, in the player. `feature:settings` has its own mapping for the
 * settings screen; a feature may not depend on another feature, so the strings are referenced here
 * directly instead of sharing a function.
 */
internal fun OrientationLock.playerLabelRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.settings_orientation_auto
    OrientationLock.PORTRAIT -> R.string.settings_orientation_portrait
    OrientationLock.LANDSCAPE -> R.string.settings_orientation_landscape
    OrientationLock.REVERSE_LANDSCAPE -> R.string.settings_orientation_reverse_landscape
    OrientationLock.SENSOR -> R.string.settings_orientation_sensor
}

/** The short names the top bar shows. They are product names (SW, HW, HW+), not translatable words. */
internal fun DecoderMode.shortLabel(): String = when (this) {
    DecoderMode.SOFTWARE -> "SW"
    DecoderMode.HARDWARE -> "HW"
    DecoderMode.HARDWARE_PLUS -> "HW+"
}

/** m:ss, or h:mm:ss from an hour on. Negative input clamps to zero rather than showing a minus sign. */
internal fun clock(ms: Long): String {
    val total = (ms / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
