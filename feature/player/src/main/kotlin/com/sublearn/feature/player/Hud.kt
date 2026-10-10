package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.R

/**
 * Gesture feedback (PLY-4): brightness on the left, volume on the right, a seek readout in the
 * centre, a speed pill, and the accumulated double-tap seek on the tapped side.
 *
 * It never takes pointer input, so the gesture layer underneath keeps every touch. The numbers
 * come from [GestureHud]; this composable only draws them.
 */
@Composable
fun GestureHudLayer(hud: GestureHud?, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val enter = remember(reduce) { hudEnter(reduce) }
    val exit = remember(reduce) { hudExit(reduce) }
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hud is GestureHud.Brightness,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Dimens.hudPadding),
        ) {
            val level = (hud as? GestureHud.Brightness)?.level ?: 0f
            VerticalLevel(icon = Icons.Default.Brightness6, label = stringResource(R.string.player_brightness), level = level)
        }
        AnimatedVisibility(
            visible = hud is GestureHud.Volume,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.hudPadding),
        ) {
            val level = (hud as? GestureHud.Volume)?.level ?: 0f
            VerticalLevel(icon = Icons.AutoMirrored.Filled.VolumeUp, label = stringResource(R.string.player_volume), level = level)
        }
        AnimatedVisibility(
            visible = hud is GestureHud.Seek,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Dimens.hudPadding),
        ) {
            val seek = hud as? GestureHud.Seek
            SeekReadout(
                targetMs = seek?.targetMs ?: 0L,
                deltaMs = seek?.deltaMs ?: 0L,
                durationMs = seek?.durationMs ?: 0L,
            )
        }
        AnimatedVisibility(
            visible = hud is GestureHud.Speed,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Dimens.hudPadding),
        ) {
            val percent = (hud as? GestureHud.Speed)?.percent ?: 0
            SpeedPill(percent)
        }
        AnimatedVisibility(
            visible = hud is GestureHud.DoubleTap && !hud.forward,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Dimens.xxl),
        ) {
            val tap = hud as? GestureHud.DoubleTap
            SeekBubble(forward = false, seconds = tap?.seconds ?: 0)
        }
        AnimatedVisibility(
            visible = hud is GestureHud.DoubleTap && hud.forward,
            enter = enter,
            exit = exit,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.xxl),
        ) {
            val tap = hud as? GestureHud.DoubleTap
            SeekBubble(forward = true, seconds = tap?.seconds ?: 0)
        }
    }
}

/** Fades the readout in and out. With reduce-motion on, it appears and disappears at once. */
private fun hudEnter(reduce: Boolean): EnterTransition =
    if (reduce) EnterTransition.None else fadeIn(tween(Motion.SHORT_MS, easing = Motion.standard))

private fun hudExit(reduce: Boolean): ExitTransition =
    if (reduce) ExitTransition.None else fadeOut(tween(Motion.SHORT_MS, easing = Motion.standard))

/** A vertical level bar with its icon, used for brightness and volume. */
@Composable
private fun VerticalLevel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, level: Float) {
    val colors = LocalSubLearnColors.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(colors.strongBackdrop)
            .padding(horizontal = Dimens.md, vertical = Dimens.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = colors.controlActive)
        Box(
            modifier = Modifier
                .size(width = Dimens.hudTrackWidth, height = Dimens.hudTrackHeight)
                .clip(CircleShape)
                .background(colors.controlDisabled),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.coerceIn(0f, 1f))
                    .background(colors.controlActive),
            )
        }
        Text(
            text = "${(level.coerceIn(0f, 1f) * PERCENT).toInt()}",
            style = MaterialTheme.typography.labelLarge,
            color = colors.controlActive,
        )
    }
}

/** The seek readout: the time it lands on, the change from the start of the drag, and the total. */
@Composable
private fun SeekReadout(targetMs: Long, deltaMs: Long, durationMs: Long) {
    val colors = LocalSubLearnColors.current
    val sign = if (deltaMs < 0L) "-" else "+"
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusMd))
            .background(colors.strongBackdrop)
            .padding(horizontal = Dimens.lg, vertical = Dimens.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (durationMs > 0L) "${clock(targetMs)} / ${clock(durationMs)}" else clock(targetMs),
            style = MaterialTheme.typography.titleMedium,
            color = colors.controlActive,
        )
        Text(
            text = stringResource(R.string.player_seconds_signed, sign, (kotlin.math.abs(deltaMs) / MILLIS_PER_SECOND).toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = colors.controlIdle,
        )
    }
}

/** The speed pill shown during a two-finger drag or a long press. */
@Composable
private fun SpeedPill(percent: Int) {
    val colors = LocalSubLearnColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusPill))
            .background(colors.strongBackdrop)
            .padding(horizontal = Dimens.lg, vertical = Dimens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        Icon(
            imageVector = Icons.Default.Speed,
            contentDescription = null,
            tint = colors.controlActive,
            modifier = Modifier.size(Dimens.inlineIcon),
        )
        Text(
            text = stringResource(R.string.player_speed_value, percent),
            style = MaterialTheme.typography.titleMedium,
            color = colors.controlActive,
        )
    }
}

/** The double-tap bubble: an arrow and the seconds accumulated on this side so far. */
@Composable
private fun SeekBubble(forward: Boolean, seconds: Int) {
    val colors = LocalSubLearnColors.current
    Column(
        modifier = Modifier
            .size(Dimens.hudCircle)
            .clip(CircleShape)
            .background(colors.strongBackdrop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
            contentDescription = stringResource(if (forward) R.string.player_seek_forward else R.string.player_seek_backward),
            tint = colors.controlActive,
            modifier = Modifier.size(Dimens.inlineIcon),
        )
        Text(
            text = stringResource(R.string.player_seconds_short, seconds),
            style = MaterialTheme.typography.labelLarge,
            color = colors.controlActive,
        )
    }
}

private const val PERCENT = 100f
private const val MILLIS_PER_SECOND = 1_000L
