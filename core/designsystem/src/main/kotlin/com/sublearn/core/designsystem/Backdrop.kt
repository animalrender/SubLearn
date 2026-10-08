package com.sublearn.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The translucent plate behind subtitle text and popups.
 *
 * Alpha comes from the layer's own transparency setting, while the colour stays in the palette, so
 * a subtitle never becomes unreadable in the light theme (GEN-1).
 */
@Composable
fun SubtitleBackdrop(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    colorArgb: Long? = null,
    cornerRadiusDp: Float = 10f,
    contentPaddingHorizontal: Int = 10,
    contentPaddingVertical: Int = 4,
    content: @Composable () -> Unit,
) {
    val colors = LocalSubLearnColors.current
    val base = colorArgb?.let { Color(it) } ?: colors.subtitleBackdrop
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(base.copy(alpha = (base.alpha * alpha).coerceIn(0f, 1f)))
            .padding(horizontal = contentPaddingHorizontal.dp, vertical = contentPaddingVertical.dp),
    ) { content() }
}

/** A small pill for badges such as a CEFR level; the same shape everywhere on purpose. */
@Composable
fun BadgePill(
    label: String,
    colorArgb: Long,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(colorArgb).copy(alpha = BADGE_ALPHA))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = Color(colorArgb),
        )
    }
}
