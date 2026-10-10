package com.sublearn.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Immutable
import com.sublearn.core.settings.CefrLevel

/**
 * Semantic colour roles on top of the Material scheme, so a feature never hardcodes "that grey I
 * used on the previous screen". `null` colours mean "inherit from the theme".
 */
@Immutable
data class SubLearnColors(
    val playerScrim: Color,
    /** Subtitle text sits on the dark subtitle backdrop in both themes, so it is always light. */
    val subtitleText: Color,
    /** The letterbox around the picture. Black in both themes, because the video is the content. */
    val letterbox: Color,
    val subtitleBackdrop: Color,
    val cardBackdrop: Color,
    val strongBackdrop: Color,
    val overlayOutline: Color,
    val controlIdle: Color,
    val controlActive: Color,
    val controlDisabled: Color,
    val listCurrentRow: Color,
    val listHoverRow: Color,
    val levelBadgeBackgroundAlpha: Float,
)

/** Alpha used for a CEFR badge so the tint matches both themes. */
const val BADGE_ALPHA = 0.20f

fun CefrLevel.badgeColorArgb(): Long = when (this) {
    CefrLevel.A1 -> Palette.levelColor("A1")
    CefrLevel.A2 -> Palette.levelColor("A2")
    CefrLevel.B1 -> Palette.levelColor("B1")
    CefrLevel.B2 -> Palette.levelColor("B2")
    CefrLevel.C1 -> Palette.levelColor("C1")
    CefrLevel.C2 -> Palette.levelColor("C2")
    CefrLevel.UNKNOWN -> Palette.levelColor("UNKNOWN")
}
