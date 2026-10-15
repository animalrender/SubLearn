package com.sublearn.core.designsystem

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * SubLearn's standard linear progress indicator.
 *
 * In Material3 >= 1.3.x (BOM 2024.12.01+), this wraps LinearProgressIndicator.
 * When the BOM is updated to include `drawStopIndicator` and `gapSize`, add them here
 * to remove the stop-indicator dot. Until then, this is a thin wrapper for consistency.
 *
 * All LinearProgressIndicator usage in the app should use this composable.
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
    )
}

/**
 * Indeterminate version for unknown-length operations.
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
    )
}