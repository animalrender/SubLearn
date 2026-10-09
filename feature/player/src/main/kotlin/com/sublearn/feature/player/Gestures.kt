package com.sublearn.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.GestureSlot
import kotlin.math.abs

/**
 * The surface gestures (PLY-2). Everything is expressed as a [GestureAction] so a remap in
 * settings changes the input only, never the player logic.
 *
 * Priority is fixed here, not in the mapping: a tap that starts on subtitle text is consumed by the
 * text layer and never reaches this modifier, because the text layer sits above in the z-order and
 * its pointerInput runs first.
 */
@Composable
fun GestureLayer(
    settings: AppSettings,
    enabled: Boolean,
    onAction: (GestureAction) -> Unit,
    onDrag: (GestureAction, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: (PlayerViewModel.TapSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gestures = settings.gestures
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, gestures, settings.player.doubleTapAction) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { position ->
                        val third = size.width.toFloat() / 3f
                        val side = when {
                            position.x < third -> PlayerViewModel.TapSide.LEFT
                            position.x > 2f * third -> PlayerViewModel.TapSide.RIGHT
                            else -> PlayerViewModel.TapSide.CENTER
                        }
                        onDoubleTap(side)
                    },
                    onTap = { onAction(GestureAction.TOGGLE_CONTROLS) },
                )
            }
            .pointerInput(enabled, gestures) {
                if (!enabled) return@pointerInput
                detectSurfaceDrag(
                    onDrag = { start, delta ->
                        val action = surfaceActionFor(gestures, start, delta, size.width.toFloat())
                        if (action == GestureAction.NONE) return@detectSurfaceDrag
                        val fraction = when (action) {
                            GestureAction.BRIGHTNESS, GestureAction.VOLUME -> {
                                val range = size.height.toFloat()
                                (1f - ((start.y + delta.y) / range).coerceIn(0f, 1f))
                            }
                            GestureAction.PLAYBACK_SPEED -> -delta.y / size.height.toFloat()
                            GestureAction.SEEK -> delta.x / size.width.toFloat()
                            else -> 0f
                        }
                        onDrag(action, fraction)
                    },
                    onEnd = { onDragEnd() },
                )
            },
    )
}

/** Which slot a drag belongs to, given where it started and which way it went. */
internal fun surfaceActionFor(
    gestures: com.sublearn.core.settings.GestureSettings,
    start: Offset,
    delta: Offset,
    width: Float,
): GestureAction {
    val horizontal = abs(delta.x) > abs(delta.y)
    if (horizontal) {
        return if (gestures.horizontalEnabled) gestures.actionFor(GestureSlot.HORIZONTAL) else GestureAction.NONE
    }
    // A vertical drag takes its slot from the half of the screen it started in (PLY-2).
    val slot = if (start.x < width / 2f) GestureSlot.LEFT_VERTICAL else GestureSlot.RIGHT_VERTICAL
    return if (gestures.invertVertical) {
        when (slot) {
            GestureSlot.LEFT_VERTICAL -> GestureSlot.RIGHT_VERTICAL
            GestureSlot.RIGHT_VERTICAL -> GestureSlot.LEFT_VERTICAL
            else -> slot
        }.let(gestures::actionFor)
    } else {
        gestures.actionFor(slot)
    }
}

/**
 * Tracks one finger: reports the accumulated delta while it moves, then the total at the end.
 *
 * Written by hand because Compose's ready-made drag detectors consume the down, which would break
 * the tap detection above (both live on the same node).
 */
private suspend fun PointerInputScope.detectSurfaceDrag(
    onDrag: (Offset, Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var total = Offset.Zero
        var lifted = false
        while (!lifted) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.pressed) {
                total = change.position - start
                if (total.getDistance() > viewConfiguration.touchSlop) onDrag(start, total)
                change.consume()
            } else {
                lifted = true
            }
        }
        onEnd()
    }
}

/** Waits for the tap window, then reports how many taps happened and where the last one was. */
internal suspend fun PointerInputScope.detectMultiTap(
    windowMs: Long = 320L,
    maxTaps: Int = 3,
    onTaps: (Int, Offset) -> Unit,
) {
    awaitEachGesture {
        var taps = 0
        var down = awaitFirstDown(requireUnconsumed = false)
        var position = down.position
        while (true) {
            taps++
            position = down.position
            val up = waitForUpOrCancellation()
            if (up == null || taps >= maxTaps) break
            // The next down inside the window continues the same gesture; otherwise the count is final.
            down = withTimeoutOrNull(windowMs) { awaitFirstDown(requireUnconsumed = false) } ?: break
        }
        onTaps(taps, position)
    }
}
