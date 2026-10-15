package com.sublearn.core.designsystem

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection

/**
 * SubLearn's standard linear progress indicator that removes the stop indicator dot
 * introduced in Material3 1.3.x (Compose BOM 2024.12.01).
 *
 * Uses `drawStopIndicator = {}` and `gapSize = 0.dp` for a clean bar.
 * All [LinearProgressIndicator] usage in the app should use this composable.
 */
@Composable
fun SubLearnLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = strokeCap,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/**
 * Indeterminate version for unknown-length operations (e.g. ML Kit model download).
 */
@Composable
fun SubLearnLinearProgressIndeterminate(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    LinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = strokeCap,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}