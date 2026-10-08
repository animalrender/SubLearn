package com.sublearn.core.designsystem

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

/**
 * Motion spec. Durations live here and nowhere else (GEN-1), so "reduce motion" can shorten
 * everything in one place and the player's auto-hide timings stay consistent with the UI.
 */
object Motion {
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val linear = LinearEasing

    const val MICRO_MS = 90
    const val SHORT_MS = 160
    const val STANDARD_MS = 260
    const val LONG_MS = 420
    const val OVERLAY_MS = 220
    const val POPUP_RISE_MS = 260
    const val POPUP_FADE_MS = 520
    const val SHEET_MS = 320

    fun gentleSpring(stiffness: Float = Spring.StiffnessMediumLow): Spring<Float> = spring(stiffness = stiffness)

    fun controlsEnter(reduceMotion: Boolean): EnterTransition = if (reduceMotion) {
        fadeIn(tween(MICRO_MS))
    } else {
        fadeIn(tween(SHORT_MS, easing = decelerate)) +
            slideInVertically(tween(OVERLAY_MS, easing = emphasized)) { -it / 6 }
    }

    fun controlsExit(reduceMotion: Boolean): ExitTransition = if (reduceMotion) {
        fadeOut(tween(MICRO_MS))
    } else {
        fadeOut(tween(SHORT_MS, easing = accelerate)) +
            slideOutVertically(tween(OVERLAY_MS, easing = emphasized)) { -it / 6 }
    }

    fun popupEnter(reduceMotion: Boolean, risePx: Int = 40, lifetimeScale: Float = 1f): EnterTransition = if (reduceMotion) {
        fadeIn(tween((POPUP_FADE_MS * 0.4f * lifetimeScale).toInt()))
    } else {
        fadeIn(tween((POPUP_RISE_MS * lifetimeScale).toInt(), easing = decelerate)) +
            slideInVertically(tween((POPUP_RISE_MS * lifetimeScale).toInt(), easing = emphasized)) { (risePx * 1f).toInt() }
    }

    fun popupExit(reduceMotion: Boolean, lifetimeScale: Float = 1f): ExitTransition =
        fadeOut(tween(if (reduceMotion) MICRO_MS else (POPUP_FADE_MS * lifetimeScale).toInt(), easing = accelerate))

    fun cardEnter(reduceMotion: Boolean): EnterTransition = if (reduceMotion) {
        fadeIn(tween(MICRO_MS))
    } else {
        fadeIn(tween(STANDARD_MS, easing = decelerate)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(SHEET_MS, easing = emphasized))
    }
}
