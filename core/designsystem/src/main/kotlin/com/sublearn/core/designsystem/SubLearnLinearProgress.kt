package com.sublearn.core.designsystem

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** Continuous progress with no Material stop dot or gap (GEN-1); use this on every surface. */
@Composable
fun SubLearnLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    LinearProgressIndicator(
        progress = progress,
        drawStopIndicator = {},
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = strokeCap,
        gapSize = 0.dp,
    )
}

/**
 * Indeterminate version for unknown-length operations.
 */
@Composable
fun SubLearnLinearProgressIndeterminate(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    strokeCap: StrokeCap = StrokeCap.Round,
) {
    LinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = strokeCap,
        gapSize = 0.dp,
    )
}
