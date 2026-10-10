package com.sublearn.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.subtitles.TrackRole
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Who may receive a pointer on the video surface.
 *
 * `NORMAL` runs every gesture, `LAYOUT` only lets subtitle plates be dragged (SUB-3), and `OFF`
 * leaves the surface to Android (for example while PiP is showing).
 */
internal enum class GestureMode { OFF, NORMAL, LAYOUT }

/** Settings the gesture loop needs, grouped so the modifier's restart key stays one value. */
internal data class GestureTuning(
    val invertVertical: Boolean,
    val edgeExclusionPx: Float,
    val multiTapWindowMs: Long,
)

private const val LONG_PRESS_MS = 450L
private const val DOUBLE_TAP_WINDOW_MS = 280L
private const val NANOS_PER_MS = 1_000_000L
private const val PINCH_RATIO = 0.12f
private const val THIRDS = 3f

/** The tag [styleWords] puts on every word, so a tap can ask which word is under the finger. */
internal const val SUBTITLE_WORD_TAG = "word"

/** Straight-line length of a vector, used for drag slop and pinch distance. */
private fun Offset.length(): Float = sqrt(x * x + y * y)

/**
 * Hit testing for everything drawn over the video.
 *
 * Elements report their [LayoutCoordinates] rather than a computed rectangle, and the rectangle is
 * resolved only when a pointer arrives. That keeps the registry correct through rotation and
 * recomposition, when layout callbacks can arrive in any order.
 */
internal class PlayerHitRegistry {
    private var surface: LayoutCoordinates? = null
    private val subtitles = LinkedHashMap<TrackRole, SubtitleSurface>()
    private val blockers = LinkedHashMap<Any, LayoutCoordinates>()

    fun attachSurface(coordinates: LayoutCoordinates) {
        surface = coordinates
    }

    fun putSubtitlePlate(role: TrackRole, coordinates: LayoutCoordinates) {
        subtitles.getOrPut(role) { SubtitleSurface() }.plate = coordinates
    }

    fun putSubtitleText(role: TrackRole, coordinates: LayoutCoordinates, layout: TextLayoutResult?) {
        val entry = subtitles.getOrPut(role) { SubtitleSurface() }
        entry.text = coordinates
        entry.layout = layout
    }

    fun forgetSubtitle(role: TrackRole) {
        subtitles.remove(role)
    }

    fun putBlocker(key: Any, coordinates: LayoutCoordinates) {
        blockers[key] = coordinates
    }

    fun removeBlocker(key: Any) {
        blockers.remove(key)
    }

    /** Chrome and popups take their own touches; the surface must not also react to them. */
    fun isBlocked(position: Offset): Boolean = blockers.values.any { local(it)?.contains(position) == true }

    fun subtitleAt(position: Offset): TrackRole? =
        subtitles.entries.firstOrNull { local(it.value.plate)?.contains(position) == true }?.key

    /** The word under [position] in the layer's text, or null when the tap landed between words. */
    fun wordAt(role: TrackRole, position: Offset): String? {
        val entry = subtitles[role] ?: return null
        val bounds = local(entry.text) ?: return null
        val layout = entry.layout ?: return null
        if (!bounds.contains(position)) return null
        val offset = layout.getOffsetForPosition(position - bounds.topLeft)
        return layout.layoutInput.text.getStringAnnotations(SUBTITLE_WORD_TAG, offset, offset).firstOrNull()?.item
    }

    private fun local(coordinates: LayoutCoordinates?): Rect? {
        val parent = surface ?: return null
        if (coordinates == null || !coordinates.isAttached || !parent.isAttached) return null
        return parent.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    private class SubtitleSurface {
        var plate: LayoutCoordinates? = null
        var text: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
    }
}

/** Registers a chrome element as a region the surface must ignore. Removed when the element leaves. */
@Composable
internal fun Modifier.blocksGestures(registry: PlayerHitRegistry?): Modifier {
    if (registry == null) return this
    val key = remember { Any() }
    DisposableEffect(registry, key) {
        onDispose { registry.removeBlocker(key) }
    }
    return onGloballyPositioned { registry.putBlocker(key, it) }
}

/**
 * The one gesture owner for the video surface, in priority order:
 *
 * 1. Chrome that is on screen keeps its own touches.
 * 2. A subtitle plate takes taps (word, line, block) or, in layout mode, drags.
 * 3. Otherwise the surface runs its gestures: tap, double tap, long press, horizontal seek, the
 *    vertical brightness and volume sides, two-finger speed and pinch.
 *
 * Every continuous gesture reports *totals since it began*, so a dropped event can never make
 * seeking or volume drift; the ViewModel applies those totals to values captured at the start.
 */
@Composable
internal fun Modifier.playerGestures(
    mode: GestureMode,
    registry: PlayerHitRegistry,
    viewModel: PlayerViewModel,
    tuning: GestureTuning,
    readLevel: (GestureAction) -> Float,
): Modifier {
    val currentRead by rememberUpdatedState(readLevel)
    return pointerInput(mode, tuning, registry, viewModel) {
        if (mode == GestureMode.OFF) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (registry.isBlocked(down.position)) return@awaitEachGesture
            val subtitle = registry.subtitleAt(down.position)
            if (subtitle != null) {
                if (mode == GestureMode.LAYOUT) {
                    layoutDrag(subtitle, down, viewModel)
                } else {
                    subtitleTaps(subtitle, down, registry, viewModel, tuning.multiTapWindowMs)
                }
                return@awaitEachGesture
            }
            if (mode != GestureMode.NORMAL) return@awaitEachGesture
            val area = this@pointerInput.size
            val edge = tuning.edgeExclusionPx
            // Starts at the screen edge belong to the system back gesture, so they never become ours.
            if (down.position.x < edge || down.position.x > area.width - edge) return@awaitEachGesture
            surfaceGesture(down, area, registry, viewModel, tuning, currentRead)
        }
    }
}

private enum class Axis { PENDING, HORIZONTAL, LEFT, RIGHT }

private suspend fun AwaitPointerEventScope.surfaceGesture(
    down: PointerInputChange,
    area: IntSize,
    registry: PlayerHitRegistry,
    viewModel: PlayerViewModel,
    tuning: GestureTuning,
    readLevel: (GestureAction) -> Float,
) {
    val slop = viewConfiguration.touchSlop
    val origin = down.position
    val startedAt = System.nanoTime()
    val width = area.width.toFloat()
    val height = area.height.toFloat()
    var axis = Axis.PENDING
    var startLevel = 0f
    var longPressed = false
    var multiTouch = false
    var total = Offset.Zero
    var pinchStart = 0f
    var pinchFired = false
    var midStartY = 0f

    while (true) {
        val event: PointerEvent
        if (axis == Axis.PENDING && !longPressed && !multiTouch) {
            val remainingMs = LONG_PRESS_MS - (System.nanoTime() - startedAt) / NANOS_PER_MS
            val next = if (remainingMs > 0L) withTimeoutOrNull(remainingMs) { awaitPointerEvent() } else null
            if (next == null) {
                // Held still long enough: fast forward until the finger lifts.
                longPressed = true
                viewModel.onHoldSpeed(true)
                continue
            }
            event = next
        } else {
            event = awaitPointerEvent()
        }

        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break

        if (pressed.size >= 2) {
            if (!multiTouch) {
                multiTouch = true
                if (longPressed) {
                    longPressed = false
                    viewModel.onHoldSpeed(false)
                }
                pinchStart = distanceOf(pressed)
                midStartY = midYOf(pressed)
                viewModel.onTwoFingerStart()
            }
            val distance = distanceOf(pressed)
            if (!pinchFired && pinchStart > slop && abs(distance / pinchStart - 1f) > PINCH_RATIO) {
                pinchFired = true
                viewModel.onPinch()
            }
            viewModel.onTwoFingerSpeed(midYOf(pressed) - midStartY, height)
            pressed.forEach { it.consume() }
            continue
        }

        // A second finger has been down in this gesture: the rest of it belongs to the two-finger path.
        if (multiTouch) continue

        val change = pressed.first()
        total = change.position - origin
        if (longPressed) {
            change.consume()
            continue
        }
        if (axis == Axis.PENDING && total.length() > slop) {
            axis = when {
                abs(total.x) > abs(total.y) -> Axis.HORIZONTAL
                origin.x < width / 2f -> Axis.LEFT
                else -> Axis.RIGHT
            }
            when (axis) {
                Axis.HORIZONTAL -> viewModel.onHorizontalDragStart()
                Axis.LEFT -> startLevel = readLevel(viewModel.sideAction(PlayerViewModel.TapSide.LEFT))
                Axis.RIGHT -> startLevel = readLevel(viewModel.sideAction(PlayerViewModel.TapSide.RIGHT))
                Axis.PENDING -> Unit
            }
        }
        when (axis) {
            Axis.HORIZONTAL -> {
                viewModel.onHorizontalDragUpdate(total.x, width)
                change.consume()
            }
            Axis.LEFT, Axis.RIGHT -> {
                // A full-height drag covers the whole range; dragging up raises the level unless inverted.
                val direction = if (tuning.invertVertical) 1f else -1f
                val side = if (axis == Axis.LEFT) PlayerViewModel.TapSide.LEFT else PlayerViewModel.TapSide.RIGHT
                viewModel.onSideDrag(side, startLevel + direction * (total.y / height))
                change.consume()
            }
            Axis.PENDING -> Unit
        }
    }

    when (axis) {
        Axis.HORIZONTAL -> viewModel.onHorizontalDragEnd()
        Axis.LEFT, Axis.RIGHT -> viewModel.onSideDragEnd()
        Axis.PENDING -> Unit
    }
    if (multiTouch) viewModel.onTwoFingerEnd()
    if (longPressed) viewModel.onHoldSpeed(false)
    if (axis == Axis.PENDING && !longPressed && !multiTouch) {
        handleSurfaceTap(area, registry, viewModel)
    }
}

/** A tap waits briefly for a second one: a single tap shows the controls, a pair is a double tap. */
private suspend fun AwaitPointerEventScope.handleSurfaceTap(
    area: IntSize,
    registry: PlayerHitRegistry,
    viewModel: PlayerViewModel,
) {
    val second = withTimeoutOrNull(DOUBLE_TAP_WINDOW_MS) { awaitFirstDown(requireUnconsumed = false) }
    if (second == null) {
        viewModel.onSurfaceTap()
        return
    }
    if (registry.isBlocked(second.position) || registry.subtitleAt(second.position) != null) {
        viewModel.onSurfaceTap()
        return
    }
    if (waitForUpOrCancellation() == null) return
    viewModel.onSurfaceDoubleTap(sideOf(second.position.x, area.width.toFloat()))
}

private fun sideOf(x: Float, width: Float): PlayerViewModel.TapSide {
    val third = width / THIRDS
    return when {
        x < third -> PlayerViewModel.TapSide.LEFT
        x > third * 2f -> PlayerViewModel.TapSide.RIGHT
        else -> PlayerViewModel.TapSide.CENTER
    }
}

/**
 * Taps on a subtitle: 1 tap = word (or line when no word is under the finger), 2 = line, 3 = block.
 * The count keeps going while taps land on the same plate inside the multi-tap window.
 */
private suspend fun AwaitPointerEventScope.subtitleTaps(
    role: TrackRole,
    first: PointerInputChange,
    registry: PlayerHitRegistry,
    viewModel: PlayerViewModel,
    windowMs: Long,
) {
    val slop = viewConfiguration.touchSlop
    var taps = 0
    var pointer = first
    var lastPosition = first.position
    while (true) {
        val up = waitForUpOrCancellation() ?: return
        // A press that travelled is a drag over the text, not a tap.
        if ((up.position - pointer.position).length() > slop) return
        taps += 1
        lastPosition = up.position
        if (taps >= MAX_TAP_COUNT) break
        val next = withTimeoutOrNull(windowMs) { awaitFirstDown(requireUnconsumed = false) } ?: break
        if (registry.subtitleAt(next.position) != role) break
        pointer = next
    }
    val word = if (taps == 1) registry.wordAt(role, lastPosition) else null
    viewModel.onSubtitleTap(role, taps, word)
}

/** Layout mode: the plate follows the finger in dp, and the position is persisted when the finger lifts. */
private suspend fun AwaitPointerEventScope.layoutDrag(
    role: TrackRole,
    down: PointerInputChange,
    viewModel: PlayerViewModel,
) {
    var last = down.position
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val delta = change.position - last
        last = change.position
        if (delta != Offset.Zero) viewModel.onLayerDrag(role, delta.x / density, delta.y / density)
        change.consume()
    }
    viewModel.onLayerDragEnd(role)
}

private const val MAX_TAP_COUNT = 3

private fun distanceOf(pointers: List<PointerInputChange>): Float =
    (pointers[0].position - pointers[1].position).length()

private fun midYOf(pointers: List<PointerInputChange>): Float =
    (pointers[0].position.y + pointers[1].position.y) / 2f
