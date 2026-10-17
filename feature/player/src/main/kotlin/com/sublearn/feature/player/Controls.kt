package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.R
import com.sublearn.core.settings.DockMode
import com.sublearn.core.player.PlaylistRepeat
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.subtitles.TrackRole
import kotlin.math.roundToInt

/**
 * Fades and slides the chrome in and out. Timing and the reduce-motion variant come from
 * [Motion]; this file never picks a duration of its own.
 */
@Composable
internal fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    fromTop: Boolean = true,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = Motion.controlsEnter(reduce, fromTop),
        exit = Motion.controlsExit(reduce, fromTop),
        modifier = modifier,
    ) { content() }
}

/**
 * A 44 dp target around one icon. Only a few controls have a second meaning on long press, so
 * [onLongClick] is optional and the default is a plain tap.
 */
@OptIn(ExperimentalFoundationApi::class)
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

/** Main top toolbar; docked actions share its row and scroll without pushing off-screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TopChrome(
    ui: PlayerUi,
    onBack: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    menuActions: List<PlayerMenuItem>,
    onQuickTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onDecoderTap: () -> Unit,
    onDecoderLongPress: () -> Unit,
    onReorder: (QuickActionId, DockMode, Int) -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSubLearnColors.current
    val topActions = ui.settings.quickActions.visibleIn(DockMode.TOP).map { it.first }
        .filterNot { it.fixedInPlaybackRow }
        .take(MAX_TOP_ROW_ACTIONS)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .blocksGestures(registry)
            .background(chromeBrush(towardsBottom = false))
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.playerEdgeInset, vertical = Dimens.xs),
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
            if (topActions.isNotEmpty()) {
                QuickActionRail(
                    ui = ui,
                    actions = topActions,
                    dock = DockMode.TOP,
                    onTap = onQuickTap,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                    onReorder = onReorder,
                    registry = registry,
                    modifier = Modifier.weight(1f),
                )
            }
            ChromeIconButton(
                icon = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.player_more),
                onClick = { onMenuOpenChange(true) },
            )
            ChromeIconButton(
                icon = Icons.Default.Subtitles,
                contentDescription = stringResource(R.string.player_subtitle_tracks),
                onClick = { onQuickTap(QuickActionId.SUBTITLE_TRACKS) },
            )
            ChromeIconButton(
                icon = Icons.Default.GraphicEq,
                contentDescription = stringResource(R.string.player_audio_tracks),
                onClick = { onQuickTap(QuickActionId.AUDIO_TRACK) },
            )
            Surface(
                modifier = Modifier.size(Dimens.touchTarget).combinedClickable(
                    onClick = onDecoderTap,
                    onLongClick = onDecoderLongPress,
                ),
                shape = CircleShape,
                color = colors.strongBackdrop,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (ui.playback.decoder == com.sublearn.core.settings.DecoderMode.SOFTWARE) "SW" else "HW",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.controlActive,
                    )
                }
            }
            Box {
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
}

/** One row of the overflow menu. Built by the screen so the menu lists every action in one place. */
data class PlayerMenuItem(
    val label: Int,
    val icon: ImageVector,
    val checked: Boolean,
    val onClick: () -> Unit,
)

/**
 * Three fixed bottom zones: side actions remain at the edges while the seven transport controls
 * occupy the centre. The row never scrolls; optional user actions are capped in the edge zones.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BottomChrome(
    ui: PlayerUi,
    onScrubStart: () -> Unit,
    onScrubPreview: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPreviousVideo: () -> Unit,
    onNextVideo: () -> Unit,
    onPreviousBlock: () -> Unit,
    onNextBlock: () -> Unit,
    onToggleRemainingTime: () -> Unit,
    onQuickTap: (QuickActionId) -> Unit,
    onAspectTap: () -> Unit,
    onAspectLongPress: () -> Unit,
    onToggleLock: () -> Unit,
    onReorder: (QuickActionId, DockMode, Int) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val state = ui.playback
    val live = state.isLive || state.durationMs <= 0L
    val edgeActions = ui.settings.quickActions.visibleIn(DockMode.PLAYBACK).map { it.first }
        .filterNot { it.fixedInPlaybackRow }
        .take(MAX_PLAYBACK_EDGE_ACTIONS)
    val leftActions = edgeActions.take((edgeActions.size + 1) / 2)
    val rightActions = edgeActions.drop(leftActions.size)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .blocksGestures(registry)
            .background(chromeBrush(towardsBottom = true))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = Dimens.playerEdgeInset),
    ) {
        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            progress = state.progressFraction,
            buffered = state.bufferedFraction,
            live = live,
            showRemainingTime = ui.settings.player.showRemainingTime,
            onScrubStart = onScrubStart,
            onScrubPreview = onScrubPreview,
            onScrubEnd = onScrubEnd,
            onToggleRemainingTime = onToggleRemainingTime,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val transportIconSize = (maxWidth / 20f)
                .coerceIn(MIN_TRANSPORT_ICON_DP.dp, MAX_TRANSPORT_ICON_DP.dp)
            val playIconSize = (transportIconSize * 1.3f).coerceAtMost(Dimens.touchTarget)
            val aspectSpec = ui.settings.quickActions.spec(QuickActionId.ASPECT_RATIO)
            Row(
                modifier = Modifier.fillMaxWidth().height(Dimens.touchTarget + Dimens.sm)
                    .onGloballyPositioned { registry.putQuickDock(DockMode.PLAYBACK, it) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1.5f),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChromeIconButton(
                        icon = if (ui.locked) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = stringResource(
                            if (ui.locked) R.string.player_unlock else R.string.player_lock,
                        ),
                        onClick = onToggleLock,
                        selected = ui.locked,
                        modifier = Modifier.size(transportIconSize),
                    )
                    QuickActionButton(
                        ui,
                        QuickActionId.REPEAT_BLOCK,
                        onQuickTap,
                        onHoldStart,
                        onHoldEnd,
                        DockMode.PLAYBACK,
                        transportIconSize,
                        onReorder = { _, _, _ -> },
                    )
                    leftActions.forEach { id ->
                        QuickActionButton(
                            ui,
                            id,
                            onQuickTap,
                            onHoldStart,
                            onHoldEnd,
                            DockMode.PLAYBACK,
                            transportIconSize,
                            onReorder,
                            registry,
                        )
                    }
                }
                Row(
                    modifier = Modifier.weight(3.5f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChromeIconButton(
                        icon = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous_video),
                        onClick = onPreviousVideo,
                        enabled = state.playlistIndex > 0 || state.positionMs > 2_000L || state.repeat == PlaylistRepeat.ALL,
                        modifier = Modifier.size(transportIconSize),
                    )
                    ChromeIconButton(
                        icon = Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.player_seek_backward),
                        onClick = { onSeekBy(-ui.settings.player.seekStepMs) },
                        modifier = Modifier.size(transportIconSize),
                    )
                    ChromeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_previous_subtitle),
                        onClick = onPreviousBlock,
                        enabled = ui.canStepBySubtitle,
                        modifier = Modifier.size(transportIconSize),
                    )
                    ChromeIconButton(
                        icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        onClick = onTogglePlay,
                        modifier = Modifier.size(playIconSize),
                    )
                    ChromeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.player_next_subtitle),
                        onClick = onNextBlock,
                        enabled = ui.canStepBySubtitle,
                        modifier = Modifier.size(transportIconSize),
                    )
                    ChromeIconButton(
                        icon = Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.player_seek_forward),
                        onClick = { onSeekBy(ui.settings.player.seekStepMs) },
                        modifier = Modifier.size(transportIconSize),
                    )
                    ChromeIconButton(
                        icon = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.player_next_video),
                        onClick = onNextVideo,
                        enabled = state.playlistIndex < state.playlist.lastIndex || state.repeat == PlaylistRepeat.ALL,
                        modifier = Modifier.size(transportIconSize),
                    )
                }
                Row(
                    modifier = Modifier.weight(2f),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xs, Arrangement.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rightActions.forEach { id ->
                        QuickActionButton(
                            ui,
                            id,
                            onQuickTap,
                            onHoldStart,
                            onHoldEnd,
                            DockMode.PLAYBACK,
                            transportIconSize,
                            onReorder,
                            registry,
                        )
                    }
                    Surface(
                        modifier = Modifier.size(aspectSpec.sizeDp.dp.coerceAtMost(transportIconSize)).combinedClickable(
                            onClick = onAspectTap,
                            onLongClick = onAspectLongPress,
                        ),
                        shape = CircleShape,
                        color = LocalSubLearnColors.current.strongBackdrop.copy(
                            alpha = 1f - aspectSpec.transparencyPercent / 100f,
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = state.aspect.name.take(4),
                                style = MaterialTheme.typography.labelSmall,
                                color = aspectSpec.iconTintArgb?.let { androidx.compose.ui.graphics.Color(it) }
                                    ?: LocalSubLearnColors.current.controlActive,
                            )
                        }
                    }
                    QuickActionButton(
                        ui,
                        QuickActionId.SUBTITLE_LIST,
                        onQuickTap,
                        onHoldStart,
                        onHoldEnd,
                        DockMode.PLAYBACK,
                        transportIconSize,
                    )
                }
            }
        }
    }
}

private const val MAX_TOP_ROW_ACTIONS = 1
private const val MAX_PLAYBACK_EDGE_ACTIONS = 1
private const val MIN_TRANSPORT_ICON_DP = 22f
private const val MAX_TRANSPORT_ICON_DP = 36f

/** The speed the chip reads as "normal" (no highlight). */
private const val NORMAL_SPEED_PERCENT = 100

/** Marks "not dragging" in the seekbar's fraction state. */
private const val NOT_SCRUBBING = -1f

/** A thin MX-like seek line, separate elapsed/remaining clocks and a compact circular thumb. */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    progress: Float,
    buffered: Float,
    live: Boolean,
    showRemainingTime: Boolean,
    onScrubStart: () -> Unit,
    onScrubPreview: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onToggleRemainingTime: () -> Unit,
) {
    val colors = LocalSubLearnColors.current
    var scrub by remember { mutableFloatStateOf(NOT_SCRUBBING) }
    val shown = (if (scrub >= 0f) scrub else progress).coerceIn(0f, 1f)
    val elapsedMs = if (scrub >= 0f) (scrub * durationMs).toLong() else positionMs
    val rightTime = when {
        live -> stringResource(R.string.player_live)
        showRemainingTime -> "−${clock((durationMs - elapsedMs).coerceAtLeast(0L))}"
        else -> clock(durationMs)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = clock(elapsedMs),
            style = MaterialTheme.typography.labelMedium,
            color = colors.controlActive,
            modifier = Modifier.padding(start = Dimens.xs, end = Dimens.xs),
        )
        Box(
            modifier = Modifier.weight(1f).height(Dimens.touchTarget),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .then(
                        if (live) Modifier else Modifier
                            .pointerInput(durationMs) {
                                detectTapGestures { point ->
                                    val fraction = (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    onScrubStart()
                                    scrub = fraction
                                    onScrubPreview((fraction * durationMs).toLong())
                                    onScrubEnd((fraction * durationMs).toLong())
                                    scrub = NOT_SCRUBBING
                                }
                            }
                            .pointerInput(durationMs) {
                                detectDragGestures(
                                    onDragStart = { point ->
                                        onScrubStart()
                                        val fraction = (point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        scrub = fraction
                                        onScrubPreview((fraction * durationMs).toLong())
                                    },
                                    onDrag = { change, _ ->
                                        val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        scrub = fraction
                                        onScrubPreview((fraction * durationMs).toLong())
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val target = (scrub.coerceAtLeast(0f) * durationMs).toLong()
                                        onScrubEnd(target)
                                        scrub = NOT_SCRUBBING
                                    },
                                    onDragCancel = {
                                        scrub = NOT_SCRUBBING
                                        onScrubEnd(positionMs)
                                    },
                                )
                            },
                    ),
            ) {
                val y = size.height / 2f
                val startX = 2.dp.toPx()
                val endX = (size.width - startX).coerceAtLeast(startX)
                val trackWidth = 2.dp.toPx()
                drawLine(colors.controlDisabled.copy(alpha = 0.72f), Offset(startX, y), Offset(endX, y), trackWidth, StrokeCap.Round)
                val bufferedX = startX + (endX - startX) * buffered.coerceIn(0f, 1f)
                drawLine(colors.controlIdle.copy(alpha = 0.72f), Offset(startX, y), Offset(bufferedX, y), trackWidth, StrokeCap.Round)
                val playedX = startX + (endX - startX) * shown
                drawLine(colors.controlActive, Offset(startX, y), Offset(playedX, y), trackWidth, StrokeCap.Round)
                drawCircle(colors.controlActive, radius = 4.dp.toPx(), center = Offset(playedX, y))
            }
        }
        TextButton(onClick = onToggleRemainingTime, modifier = Modifier.height(Dimens.touchTarget)) {
            Text(text = rightTime, style = MaterialTheme.typography.labelMedium, color = colors.controlIdle)
        }
    }
}

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

/**
 * The second toolbar is a single horizontally scrollable row; floating actions stay independently
 * draggable over the picture. Top and playback docks are composed into their respective bars.
 */
@Composable
internal fun QuickActionDock(
    ui: PlayerUi,
    areaWidth: Dp,
    areaHeight: Dp,
    barVisible: Boolean,
    floatingVisible: Boolean,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onMoveFloating: (QuickActionId, Float, Float) -> Unit,
    onToggleExpanded: () -> Unit,
    onReorder: (QuickActionId, DockMode, Int) -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val settings = ui.settings.quickActions
    val bar = settings.visibleIn(DockMode.BAR).map { it.first }.filterNot { it.fixedInPlaybackRow }
    val floating = settings.visibleIn(DockMode.FLOATING).map { it.first }.filterNot { it.fixedInPlaybackRow }
    Box(modifier = modifier.fillMaxSize()) {
        if (bar.isNotEmpty()) {
            ChromeVisibility(visible = barVisible, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    QuickActionRail(
                        ui = ui,
                        actions = bar,
                        dock = DockMode.BAR,
                        onTap = onTap,
                        onHoldStart = onHoldStart,
                        onHoldEnd = onHoldEnd,
                        onReorder = onReorder,
                        registry = registry,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                top = Dimens.secondActionRailTop,
                                start = Dimens.playerEdgeInset,
                                end = Dimens.playerEdgeInset,
                            ),
                    )
                    ChromeIconButton(
                        icon = if (settings.barExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.player_arrange_controls),
                        onClick = onToggleExpanded,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = Dimens.secondActionRailTop),
                    )
                }
            }
        }
        if (floatingVisible) floating.forEach { id ->
            FloatingQuickAction(
                ui = ui,
                id = id,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                onTap = onTap,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onMove = onMoveFloating,
                registry = registry,
            )
        }
    }
}

@Composable
private fun QuickActionRail(
    ui: PlayerUi,
    actions: List<QuickActionId>,
    dock: DockMode,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onReorder: (QuickActionId, DockMode, Int) -> Unit = { _, _, _ -> },
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val colors = LocalSubLearnColors.current
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { registry.putQuickDock(dock, it) }
                .horizontalScroll(scroll)
                .blocksGestures(registry)
                .padding(end = if (scroll.maxValue > 0) Dimens.touchTarget else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.dockGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { id ->
                QuickActionButton(ui, id, onTap, onHoldStart, onHoldEnd, dock, onReorder = onReorder, registry = registry)
            }
        }
        if (scroll.value > 0) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.player_more_actions_left),
                tint = colors.controlIdle,
                modifier = Modifier.align(Alignment.CenterStart).size(Dimens.inlineIcon),
            )
        }
        if (scroll.value < scroll.maxValue) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.player_more_actions_right),
                tint = colors.controlIdle,
                modifier = Modifier.align(Alignment.CenterEnd).size(Dimens.inlineIcon),
            )
        }
    }
}

/** Holding these inverts the toggle until release; holding REPEAT_BLOCK runs the auto-repeat count. */
private val HOLDABLE = setOf(
    QuickActionId.TOGGLE_LEARNING,
    QuickActionId.TOGGLE_TRANSLATION,
    QuickActionId.STOP_AT_BLOCK_END,
    QuickActionId.REPEAT_BLOCK,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickActionButton(
    ui: PlayerUi,
    id: QuickActionId,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    dock: DockMode = DockMode.BAR,
    maxButtonSize: Dp? = null,
    onReorder: (QuickActionId, DockMode, Int) -> Unit = { _, _, _ -> },
    registry: PlayerHitRegistry? = null,
) {
    val colors = LocalSubLearnColors.current
    val spec = ui.settings.quickActions.spec(id)
    val selected = quickSelected(ui, id)
    val label = stringResource(id.labelRes())
    val expanded = ui.settings.quickActions.barExpanded && dock == DockMode.BAR
    val requestedSize = spec.sizeDp.dp.coerceAtLeast(Dimens.touchTarget)
    val buttonSize = maxButtonSize?.let { requestedSize.coerceAtMost(it) } ?: requestedSize
    val iconSize = spec.sizeDp.dp.coerceIn(Dimens.inlineIcon, Dimens.iconButtonLarge)
    val currentTap by rememberUpdatedState(onTap)
    val currentStart by rememberUpdatedState(onHoldStart)
    val currentEnd by rememberUpdatedState(onHoldEnd)
    val currentReorder by rememberUpdatedState(onReorder)
    var actionCoordinates by remember(id, dock) { mutableStateOf<LayoutCoordinates?>(null) }
    Surface(
        modifier = Modifier
            .then(
                if (expanded) Modifier.height(buttonSize).widthIn(min = buttonSize)
                else Modifier.size(buttonSize),
            )
            .onGloballyPositioned { coordinates ->
                actionCoordinates = coordinates
                if (dock != DockMode.FLOATING) registry?.putQuickAction(dock, id, coordinates)
            }
            .pointerInput(id, ui.layoutMode, dock, registry) {
                if (ui.layoutMode && dock != DockMode.FLOATING) {
                    var distance = Offset.Zero
                    detectDragGestures(
                        onDrag = { change, drag ->
                            distance += drag
                            change.consume()
                        },
                        onDragEnd = {
                            val coordinates = actionCoordinates
                            val end = coordinates?.localToRoot(
                                Offset(size.width / 2f + distance.x, size.height / 2f + distance.y),
                            )
                            val target = end?.let { registry?.quickActionDropTarget(it) }
                            if (target != null && target.first != dock) {
                                currentReorder(id, target.first, target.second)
                            } else if (kotlin.math.abs(distance.x) >= size.width / 2f) {
                                currentReorder(id, dock, if (distance.x > 0f) 1 else -1)
                            }
                            distance = Offset.Zero
                        },
                        onDragCancel = { distance = Offset.Zero },
                    )
                } else {
                    var held = false
                    detectTapGestures(
                        onTap = { currentTap(id) },
                        onLongPress = {
                            if (id in HOLDABLE) {
                                held = true
                                currentStart(id)
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (held) {
                                held = false
                                currentEnd(id)
                            }
                        },
                    )
                }
            },
        shape = if (expanded) RoundedCornerShape(Dimens.radiusMd) else CircleShape,
        color = colors.strongBackdrop.copy(alpha = 1f - spec.transparencyPercent / 100f),
    ) {
        Row(
            modifier = Modifier.then(if (expanded) Modifier.padding(horizontal = Dimens.sm) else Modifier.fillMaxSize()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = id.icon(),
                contentDescription = label,
                tint = spec.iconTintArgb?.let { androidx.compose.ui.graphics.Color(it) }
                    ?: if (selected) colors.controlActive else colors.controlIdle,
                modifier = Modifier.size(iconSize),
            )
            if (expanded) {
                Spacer(Modifier.size(Dimens.xs))
                Text(label, style = MaterialTheme.typography.labelSmall, color = colors.controlActive, maxLines = 1)
            }
        }
    }
}

/**
 * A floating quick action. The drag is absolute: the start fraction is captured when the drag
 * begins and each event moves from it, so a dropped button lands where the finger is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingQuickAction(
    ui: PlayerUi,
    id: QuickActionId,
    areaWidth: Dp,
    areaHeight: Dp,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onMove: (QuickActionId, Float, Float) -> Unit,
    registry: PlayerHitRegistry,
) {
    val spec = ui.settings.quickActions.spec(id)
    val density = LocalDensity.current.density
    var draft by remember(id) { mutableStateOf<Offset?>(null) }
    val widthValue = areaWidth.value.coerceAtLeast(Dimens.touchTarget.value)
    val heightValue = areaHeight.value.coerceAtLeast(Dimens.touchTarget.value)
    val buttonSize = spec.sizeDp.dp.coerceAtLeast(Dimens.touchTarget)
        .coerceAtMost(minOf(areaWidth, areaHeight).coerceAtLeast(Dimens.touchTarget))
    val maxX = ((widthValue - buttonSize.value) / widthValue).coerceIn(0f, 1f)
    val maxY = ((heightValue - buttonSize.value) / heightValue).coerceIn(0f, 1f)
    val shown = draft ?: Offset(spec.xFraction.coerceIn(0f, maxX), spec.yFraction.coerceIn(0f, maxY))
    val currentMove by rememberUpdatedState(onMove)
    Box(
        modifier = Modifier
            .offset(x = areaWidth * shown.x.coerceIn(0f, maxX), y = areaHeight * shown.y.coerceIn(0f, maxY))
            .blocksGestures(registry)
            .pointerInput(id, areaWidth, areaHeight) {
                detectDragGestures(
                    onDragStart = { draft = Offset(spec.xFraction, spec.yFraction) },
                    onDrag = { change, drag ->
                        change.consume()
                        val base = draft ?: Offset(spec.xFraction, spec.yFraction)
                        val widthPx = areaWidth.value.coerceAtLeast(1f) * density
                        val heightPx = areaHeight.value.coerceAtLeast(1f) * density
                        draft = Offset(
                            (base.x + drag.x / widthPx).coerceIn(0f, maxX),
                            (base.y + drag.y / heightPx).coerceIn(0f, maxY),
                        )
                    },
                    onDragEnd = {
                        draft?.let { currentMove(id, it.x, it.y) }
                        draft = null
                    },
                    onDragCancel = { draft = null },
                )
            },
    ) {
        QuickActionButton(ui, id, onTap, onHoldStart, onHoldEnd, DockMode.FLOATING, maxButtonSize = buttonSize)
    }
}

/** Whether a quick action currently reads as "on", so its icon takes the active tint. */
private fun quickSelected(ui: PlayerUi, id: QuickActionId): Boolean = when (id) {
    QuickActionId.TOGGLE_LEARNING -> ui.layer(TrackRole.LEARNING).visible
    QuickActionId.TOGGLE_TRANSLATION -> ui.layer(TrackRole.TRANSLATION).visible
    QuickActionId.REPEAT_BLOCK -> ui.shadowing.active
    QuickActionId.STOP_AT_BLOCK_END -> ui.shadowing.stopAtEnd
    QuickActionId.LAYOUT_MODE -> ui.layoutMode
    QuickActionId.SUBTITLE_LIST -> ui.list.open
    QuickActionId.TOGGLE_MUTE -> ui.playback.isMuted
    else -> false
}

/** A draggable, screen-relative confirmation target that cannot be pushed off the layout banner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LayoutModeDoneButton(
    xFraction: Float,
    yFraction: Float,
    onDone: () -> Unit,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        var buttonSize by remember { mutableStateOf(IntSize.Zero) }
        var draft by remember(xFraction, yFraction) {
            mutableStateOf(Offset(xFraction.coerceIn(0f, 1f), yFraction.coerceIn(0f, 1f)))
        }
        val currentDraft by rememberUpdatedState(draft)
        val maxX = (widthPx - buttonSize.width).coerceAtLeast(0)
        val maxY = (heightPx - buttonSize.height).coerceAtLeast(0)
        TextButton(
            onClick = onDone,
            modifier = Modifier
                .offset { IntOffset((draft.x * maxX).roundToInt(), (draft.y * maxY).roundToInt()) }
                .background(LocalSubLearnColors.current.promptBackdrop, RoundedCornerShape(Dimens.radiusMd))
                .onSizeChanged { buttonSize = it }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            val dx = if (maxX == 0) 0f else amount.x / maxX
                            val dy = if (maxY == 0) 0f else amount.y / maxY
                            draft = Offset((draft.x + dx).coerceIn(0f, 1f), (draft.y + dy).coerceIn(0f, 1f))
                            change.consume()
                        },
                        onDragEnd = { onMove(currentDraft.x, currentDraft.y) },
                    )
                },
        ) {
            Text(stringResource(R.string.action_ok), color = LocalSubLearnColors.current.promptText)
        }
    }
}

/** Shown during a layout drag: the one instruction the user needs, and a way out. */
@Composable
internal fun LayoutModeBanner(
    hint: String,
    onArrange: () -> Unit,
    topVisible: Boolean,
    bottomVisible: Boolean,
    onToggleTop: () -> Unit,
    onToggleBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .wrapContentSize()
            .widthIn(max = Dimens.bannerMaxWidth)
            .padding(horizontal = Dimens.lg),
        shape = RoundedCornerShape(Dimens.radiusMd),
        color = LocalSubLearnColors.current.promptBackdrop,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Dimens.md, vertical = Dimens.xs),
            verticalArrangement = Arrangement.spacedBy(Dimens.xs),
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = LocalSubLearnColors.current.promptText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleTop, modifier = Modifier.height(Dimens.touchTarget)) {
                    Text(
                        "${stringResource(R.string.quick_dock_top)} ${if (topVisible) "✓" else "○"}",
                        color = LocalSubLearnColors.current.promptText,
                    )
                }
                TextButton(onClick = onToggleBottom, modifier = Modifier.height(Dimens.touchTarget)) {
                    Text(
                        "${stringResource(R.string.quick_dock_playback)} ${if (bottomVisible) "✓" else "○"}",
                        color = LocalSubLearnColors.current.promptText,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onArrange, modifier = Modifier.height(Dimens.touchTarget)) {
                    Text(stringResource(R.string.player_arrange_controls), color = LocalSubLearnColors.current.promptText)
                }
            }
        }
    }
}

/** A short status line; tapping it dismisses it early. The timing lives in the ViewModel. */
@Composable
internal fun Snackbarish(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRestart: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .widthIn(max = Dimens.bannerMaxWidth)
            .padding(horizontal = Dimens.lg)
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .combinedClickable(onClick = onDismiss),
        color = LocalSubLearnColors.current.promptBackdrop,
    ) {
        if (onRestart != null) {
            Row(
                modifier = Modifier.padding(start = Dimens.lg, end = Dimens.xs, top = Dimens.xs, bottom = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSubLearnColors.current.promptText,
                    textAlign = TextAlign.Start,
                )
                TextButton(onClick = onRestart, modifier = Modifier.height(Dimens.touchTarget)) {
                    Text(stringResource(R.string.player_restart_action), color = LocalSubLearnColors.current.promptText)
                }
            }
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalSubLearnColors.current.promptText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Dimens.lg, vertical = Dimens.sm),
            )
        }
    }
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
    QuickActionId.TOGGLE_MUTE -> Icons.Default.VolumeOff
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
    QuickActionId.AUDIO_TRACK -> R.string.player_audio_tracks
    QuickActionId.SUBTITLE_TRACKS -> R.string.player_subtitle_tracks
    QuickActionId.SUBTITLE_TOOLS -> R.string.subtitle_tools
    QuickActionId.SPEED -> R.string.player_speed
    QuickActionId.PICTURE_IN_PICTURE -> R.string.player_pip
    QuickActionId.SHARE -> R.string.player_share
    QuickActionId.SETTINGS -> R.string.menu_settings
    QuickActionId.TOGGLE_MUTE -> R.string.player_toggle_mute
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
