package com.sublearn.core.designsystem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Continuous-looking tracks without decorative end dots or tick dots; discrete snapping still works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubLearnSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps.coerceAtLeast(0),
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}
