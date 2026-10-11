package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.R
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.QuickActionColumn
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.QuickActionSpec
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The action bar (PLY-11): the second toolbar row and the buttons that float over the video.
 *
 * The bar is one horizontally scrolling row. Labels appear under the icons when the bar is expanded
 * (and always while layout mode is on, because a label is how the user knows what is being moved),
 * and an arrow appears at the reading end while there is more to scroll, so the row never looks like
 * it hides buttons silently. A button can be undocked by long-pressing and dragging it off the bar;
 * a floating button opens the same three-way menu by tap while arranging.
 */
@Composable
internal fun ActionBarLayer(
    ui: PlayerUi,
    areaWidth: Dp,
    areaHeight: Dp,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onMoveFloating: (QuickActionId, Float, Float) -> Unit,
    onDockChange: (QuickActionId, DockMode) -> Unit,
    onToggleExpanded: () -> Unit,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val settings = ui.settings.quickActions
    val bar = settings.visibleIn(DockMode.BAR).map { it.first }
    val floating = settings.visibleIn(DockMode.FLOATING).map { it.first }
    val expanded = settings.barExpanded || ui.layoutMode
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val origin = remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // Drop points arrive in root coordinates; the bar's own origin is what turns them into fractions.
    val toFraction: (Offset) -> Offset = { point ->
        val width = with(density) { areaWidth.toPx() }.coerceAtLeast(1f)
        val height = with(density) { areaHeight.toPx() }.coerceAtLeast(1f)
        Offset(
            ((point.x - origin.value.x) / width).coerceIn(0f, 1f),
            ((point.y - origin.value.y) / height).coerceIn(0f, 1f),
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin.value = it.positionInRoot() },
    ) {
        if (bar.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(settings.column.alignment())
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.playerEdgeInset)
                    .then(
                        if (settings.column.isTop()) {
                            Modifier.padding(top = Dimens.quickColumnTop)
                        } else {
                            Modifier.padding(bottom = Dimens.bottomChromeReserve)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionBarHandle(expanded = expanded, onToggle = onToggleExpanded)
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.dockGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    bar.forEach { id ->
                        BarAction(
                            ui = ui,
                            id = id,
                            expanded = expanded,
                            arrange = ui.layoutMode,
                            registry = registry,
                            onTap = onTap,
                            onHoldStart = onHoldStart,
                            onHoldEnd = onHoldEnd,
                            onDockChange = onDockChange,
                            onDrop = { droppedId, point ->
                                val fraction = toFraction(point)
                                onMoveFloating(droppedId, fraction.x, fraction.y)
                                onDockChange(droppedId, DockMode.FLOATING)
                            },
                        )
                    }
                }
                ActionBarMoreHint(
                    visible = scroll.canScrollForward,
                    pointsLeft = rtl,
                    onClick = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                )
            }
        }
        floating.forEach { id ->
            FloatingAction(
                ui = ui,
                id = id,
                areaWidth = areaWidth,
                areaHeight = areaHeight,
                onTap = onTap,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onMove = onMoveFloating,
                onDockChange = onDockChange,
                registry = registry,
            )
        }
    }
}

/** True for a column that hangs under the toolbar rather than above the transport row. */
private fun QuickActionColumn.isTop(): Boolean =
    this == QuickActionColumn.TOP_LEFT || this == QuickActionColumn.TOP_RIGHT

private fun QuickActionColumn.alignment(): Alignment = when (this) {
    QuickActionColumn.TOP_LEFT -> Alignment.TopStart
    QuickActionColumn.TOP_RIGHT -> Alignment.TopEnd
    QuickActionColumn.BOTTOM_LEFT -> Alignment.BottomStart
    QuickActionColumn.BOTTOM_RIGHT -> Alignment.BottomEnd
}

/** Expands or collapses the labels of the whole bar. */
@Composable
private fun ActionBarHandle(expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalSubLearnColors.current
    Surface(
        onClick = onToggle,
        modifier = Modifier.size(Dimens.iconButton),
        shape = CircleShape,
        color = colors.strongBackdrop,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (expanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                contentDescription = stringResource(
                    if (expanded) R.string.player_action_bar_labels_off else R.string.player_action_bar_labels_on,
                ),
                tint = colors.controlActive,
                modifier = Modifier.size(Dimens.inlineIcon),
            )
        }
    }
}

/**
 * The little arrow that says "the row continues". It sits at the reading end, disappears once the
 * end of the row is visible, and scrolls the row in one tap for a user who does not want to guess.
 */
@Composable
private fun ActionBarMoreHint(visible: Boolean, pointsLeft: Boolean, onClick: () -> Unit) {
    val colors = LocalSubLearnColors.current
    AnimatedVisibility(visible = visible) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(Dimens.touchTarget),
            shape = CircleShape,
            color = colors.strongBackdrop,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = if (pointsLeft) {
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = stringResource(R.string.player_action_bar_more),
                    tint = colors.controlActive,
                )
            }
        }
    }
}

/**
 * One button in the bar.
 *
 * Normally a tap runs the action and a hold inverts a toggle (SUB-1). While arranging, the same
 * finger means something else: a tap or a hold opens the docking menu, and a hold followed by a
 * drag undocks the button and drops it where the finger is. Arranging therefore takes the button's
 * own pointer handling away ([ActionButtonBody]'s `interactive` flag) instead of fighting it, which
 * is what makes the drag reliable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarAction(
    ui: PlayerUi,
    id: QuickActionId,
    expanded: Boolean,
    arrange: Boolean,
    registry: PlayerHitRegistry,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onDockChange: (QuickActionId, DockMode) -> Unit,
    onDrop: (QuickActionId, Offset) -> Unit,
) {
    val spec = ui.settings.quickActions.spec(id)
    val density = LocalDensity.current
    val menuSlop = with(density) { Dimens.touchTarget.toPx() } * MENU_SLOP_SHARE
    var origin by remember { mutableStateOf(Offset.Zero) }
    var menuOpen by remember { mutableStateOf(false) }
    val currentDrop by rememberUpdatedState(onDrop)
    Box(
        modifier = Modifier
            .blocksGestures(registry)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(id, arrange) {
                if (!arrange) return@pointerInput
                arrangeGesture(
                    onMenu = { menuOpen = true },
                    onMove = { total -> currentDrop(id, origin + total) },
                    menuSlop = menuSlop,
                )
            },
    ) {
        ActionButtonBody(
            ui = ui,
            id = id,
            spec = spec,
            // PLY-11: labels belong to the expanded bar. A floating button never carries one, because
            // it is a picture overlay first and a button second.
            labelShown = expanded || spec.showLabel,
            interactive = !arrange,
            onTapAction = { onTap(id) },
            onHoldStart = onHoldStart,
            onHoldEnd = onHoldEnd,
        )
        DockModeMenu(
            expanded = menuOpen,
            current = spec.dock,
            onDismiss = { menuOpen = false },
            onSelect = { mode ->
                menuOpen = false
                onDockChange(id, mode)
            },
        )
    }
}

/**
 * A floating action. Moving is an absolute drag (the start fraction is captured when the drag
 * begins), and while arranging a tap opens the docking menu instead of running the action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingAction(
    ui: PlayerUi,
    id: QuickActionId,
    areaWidth: Dp,
    areaHeight: Dp,
    onTap: (QuickActionId) -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
    onMove: (QuickActionId, Float, Float) -> Unit,
    onDockChange: (QuickActionId, DockMode) -> Unit,
    registry: PlayerHitRegistry,
) {
    val spec = ui.settings.quickActions.spec(id)
    val density = LocalDensity.current.density
    var draft by remember(id) { mutableStateOf<Offset?>(null) }
    var menuOpen by remember(id) { mutableStateOf(false) }
    val shown = draft ?: Offset(spec.xFraction, spec.yFraction)
    val currentMove by rememberUpdatedState(onMove)
    val arrange = ui.layoutMode
    val menuSlop = with(LocalDensity.current) { Dimens.touchTarget.toPx() } * MENU_SLOP_SHARE
    Box(
        modifier = Modifier
            .offset(x = areaWidth * shown.x.coerceIn(0f, 1f), y = areaHeight * shown.y.coerceIn(0f, 1f))
            .blocksGestures(registry)
            .pointerInput(id, arrange, areaWidth, areaHeight) {
                if (!arrange) return@pointerInput
                arrangeGesture(
                    onMenu = { menuOpen = true },
                    onMove = { total ->
                        val widthPx = areaWidth.value.coerceAtLeast(1f) * density
                        val heightPx = areaHeight.value.coerceAtLeast(1f) * density
                        val moved = Offset(
                            (spec.xFraction + total.x / widthPx).coerceIn(0f, 1f),
                            (spec.yFraction + total.y / heightPx).coerceIn(0f, 1f),
                        )
                        draft = moved
                        currentMove(id, moved.x, moved.y)
                        draft = null
                    },
                    menuSlop = menuSlop,
                )
            },
    ) {
        ActionButtonBody(
            ui = ui,
            id = id,
            spec = spec,
            labelShown = false,
            interactive = !arrange,
            onTapAction = { onTap(id) },
            onHoldStart = onHoldStart,
            onHoldEnd = onHoldEnd,
        )
        DockModeMenu(
            expanded = menuOpen,
            current = spec.dock,
            onDismiss = { menuOpen = false },
            onSelect = { mode ->
                menuOpen = false
                onDockChange(id, mode)
            },
        )
    }
}

/**
 * The circle itself: icon, and a label when the bar is expanded. A long press inverts the toggle
 * until release (SUB-1), which is why the press state is watched here rather than in the caller.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButtonBody(
    ui: PlayerUi,
    id: QuickActionId,
    spec: QuickActionSpec,
    /** PLY-11: whether this button's label is drawn under it. */
    labelShown: Boolean,
    /** False while arranging: the button only draws, and the arrange gesture owns the finger. */
    interactive: Boolean,
    onTapAction: () -> Unit,
    onHoldStart: (QuickActionId) -> Unit,
    onHoldEnd: (QuickActionId) -> Unit,
) {
    val colors = LocalSubLearnColors.current
    val selected = quickSelected(ui, id)
    val label = stringResource(id.labelRes())
    val alpha = 1f - spec.transparencyPercent / FULL_PERCENT
    val currentTap by rememberUpdatedState(onTapAction)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(spec.sizeDp.dp)
                .then(
                    if (interactive) {
                        Modifier.pointerInput(id) {
                            var held = false
                            detectTapGestures(
                                onTap = { currentTap() },
                                onLongPress = {
                                    if (id in HOLDABLE) {
                                        held = true
                                        currentHoldStart(id)
                                    }
                                },
                                onPress = {
                                    tryAwaitRelease()
                                    if (held) {
                                        held = false
                                        currentHoldEnd(id)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            shape = CircleShape,
            color = colors.strongBackdrop.copy(alpha = alpha),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = id.icon(),
                    contentDescription = label,
                    tint = if (selected) colors.controlActive else colors.controlIdle,
                    modifier = Modifier.size(iconSizeFor(spec)),
                )
            }
        }
        if (labelShown) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.controlActive else colors.controlIdle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(Dimens.actionBarLabelWidth),
            )
        }
    }
}

/**
 * The three docking choices of one button (SUB-2, PLY-11). It is a menu rather than a trip to the
 * settings screen, because "this button should float" is a decision made while looking at it.
 */
@Composable
private fun DockModeMenu(
    expanded: Boolean,
    current: DockMode,
    onDismiss: () -> Unit,
    onSelect: (DockMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DockMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(stringResource(mode.playerLabelRes())) },
                leadingIcon = if (mode == current) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon)) }
                } else {
                    null
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

/**
 * The arrange-mode gesture, used by the bar buttons and by the floating ones.
 *
 * A press that lifts before the long-press timeout, or a hold that never moves, asks for the
 * docking menu. A hold followed by movement reports the total drift, and movement before the
 * timeout is left to the row underneath, so scrolling the bar still works while arranging.
 */
private suspend fun PointerInputScope.arrangeGesture(
    onMenu: () -> Unit,
    onMove: (Offset) -> Unit,
    menuSlop: Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val startedAt = System.nanoTime()
        var drift = Offset.Zero
        var dragging = false
        var leftToTheRow = false
        while (true) {
            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
            val remainingMs = viewConfiguration.longPressTimeoutMillis - elapsedMs
            val event = if (dragging || remainingMs <= 0L) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
            }
            if (event == null) {
                // The hold lasted: from here on the finger carries the button.
                dragging = true
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val total = change.position - down.position
            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                leftToTheRow = true
                break
            }
            if (dragging) {
                drift += change.position - change.previousPosition
                change.consume()
            }
        }
        when {
            leftToTheRow -> Unit
            dragging && drift.getDistance() >= menuSlop -> onMove(drift)
            else -> onMenu()
        }
    }
}

/** Icon size inside a quick action: the circle may grow, the glyph keeps its optical weight. */
private fun iconSizeFor(spec: QuickActionSpec): Dp =
    if (spec.sizeDp >= Dimens.iconButtonLarge.value) Dimens.xl else Dimens.inlineIcon

/** The dock labels live here because a feature may not depend on `feature:settings`. */
internal fun DockMode.playerLabelRes(): Int = when (this) {
    DockMode.BAR -> R.string.player_action_dock_bar
    DockMode.FLOATING -> R.string.player_action_dock_float
    DockMode.HIDDEN -> R.string.player_action_dock_hide
}

/** How far a long press may drift before it counts as a drag instead of a menu request. */
private const val MENU_SLOP_SHARE = 0.4f

private const val NANOS_PER_MS = 1_000_000L

private const val FULL_PERCENT = 100f

/** Holding these inverts the toggle until release; holding REPEAT_BLOCK runs the auto-repeat count. */
private val HOLDABLE = setOf(
    QuickActionId.TOGGLE_LEARNING,
    QuickActionId.TOGGLE_TRANSLATION,
    QuickActionId.STOP_AT_BLOCK_END,
    QuickActionId.REPEAT_BLOCK,
)
