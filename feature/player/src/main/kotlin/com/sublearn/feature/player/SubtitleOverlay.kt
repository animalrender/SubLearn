package com.sublearn.feature.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.SubtitleBackdrop
import com.sublearn.core.designsystem.toCompose
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.WordStyle
import com.sublearn.core.settings.WordStyleSettings
import com.sublearn.core.subtitles.Tokenizer
import com.sublearn.core.subtitles.TrackRole

/** Percent to fraction, shared by scale and transparency. */
private const val PERCENT = 100f

/**
 * Both subtitle layers, drawn by SubLearn rather than by the player (D-8), so every word can be
 * touched (PLY-7, SUB-4).
 *
 * This file only draws and reports geometry. Touches are routed by [PlayerGestures] through the
 * [PlayerHitRegistry] that each plate and text block registers with, so there is one place that
 * decides what a tap means.
 */
@Composable
internal fun SubtitleLayerStack(
    ui: PlayerUi,
    registry: PlayerHitRegistry,
    modifier: Modifier = Modifier,
) {
    val roles = when (ui.settings.subtitleLayer(TrackRole.LEARNING).stackDirection) {
        LayerStackDirection.ABOVE -> listOf(TrackRole.LEARNING, TrackRole.TRANSLATION)
        LayerStackDirection.BELOW -> listOf(TrackRole.TRANSLATION, TrackRole.LEARNING)
    }
    Box(modifier = modifier.fillMaxSize()) {
        roles.forEach { role ->
            val layer = ui.layer(role)
            val text = layer.text
            if (layer.visible && !text.isNullOrBlank()) {
                SubtitlePlate(role = role, text = text, ui = ui, registry = registry)
            }
        }
    }
}

/**
 * One layer's plate. The plate is anchored by its placement and offset by the user's drag; in
 * layout mode it gets an outline so the user can see what they are moving.
 */
@Composable
private fun BoxScope.SubtitlePlate(
    role: TrackRole,
    text: String,
    ui: PlayerUi,
    registry: PlayerHitRegistry,
) {
    val settings = ui.settings
    val layer = ui.layer(role)
    val placement = layer.placement
    val colors = LocalSubLearnColors.current
    val isLearning = role == TrackRole.LEARNING
    val surface = if (isLearning) FontSurface.SUBTITLE_LEARNING else FontSurface.SUBTITLE_TRANSLATION
    val spec = settings.fontFor(surface, if (isLearning) SubtitleLayerRole.LEARNING else SubtitleLayerRole.NATIVE)
    val scale = LocalAppFontScale.current * (layer.scalePercent / PERCENT)
    val style = remember(spec, scale) { spec.toTextStyle(scale) }
    val annotated = remember(text, ui.wordStates, settings.wordStyles) {
        styleWords(text, ui.wordStates, settings.wordStyles)
    }
    val refs = remember(role) { PlateRefs() }
    DisposableEffect(role, registry) {
        onDispose { registry.forgetSubtitle(role) }
    }
    val outline = if (ui.layoutMode) {
        Modifier.border(Dimens.strokeWidth, colors.overlayOutline, RoundedCornerShape(spec.cornerRadiusDp.dp))
    } else {
        Modifier
    }
    SubtitleBackdrop(
        modifier = placed(placement)
            .then(outline)
            .onGloballyPositioned { coordinates ->
                refs.plate = coordinates
                registry.putSubtitlePlate(role, coordinates)
            },
        alpha = (1f - layer.transparencyPercent / PERCENT).coerceIn(0f, 1f),
        colorArgb = spec.backgroundColorArgb,
        cornerRadiusDp = spec.cornerRadiusDp,
    ) {
        Text(
            text = annotated,
            style = style.copy(textAlign = placement.textAlign()),
            color = spec.colorArgb?.let { Color(it) } ?: colors.subtitleText,
            maxLines = settings.subtitles.maxLinesPerLayer,
            onTextLayout = { layout: TextLayoutResult ->
                refs.layout = layout
                refs.text?.let { registry.putSubtitleText(role, it, layout) }
            },
            modifier = Modifier.onGloballyPositioned { coordinates: LayoutCoordinates ->
                refs.text = coordinates
                refs.layout?.let { registry.putSubtitleText(role, coordinates, it) }
            },
        )
    }
}

/** The last coordinates and text layout reported for one plate, kept so either can arrive first. */
private class PlateRefs {
    var plate: LayoutCoordinates? = null
    var text: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
}

/**
 * Anchors the plate inside the video area and applies the user's offset.
 *
 * The offset is stored relative to the anchor it was dragged from, so it is mirrored for END and
 * BOTTOM anchors: a positive stored value always means "further from the anchor edge". This is the
 * exact inverse of [SubtitlePlacement] movement in [PlayerViewModel] (see `movedBy`).
 */
private fun BoxScope.placed(placement: SubtitlePlacement): Modifier {
    val horizontal = when (placement.horizontal) {
        SubtitleHorizontalAnchor.START -> -1f
        SubtitleHorizontalAnchor.CENTER -> 0f
        SubtitleHorizontalAnchor.END -> 1f
    }
    val vertical = when (placement.vertical) {
        SubtitleVerticalAnchor.TOP -> -1f
        SubtitleVerticalAnchor.CENTER -> 0f
        SubtitleVerticalAnchor.BOTTOM -> 1f
    }
    val x: Dp = (if (placement.horizontal == SubtitleHorizontalAnchor.END) -placement.offsetXDp else placement.offsetXDp).dp
    val y: Dp = (if (placement.vertical == SubtitleVerticalAnchor.BOTTOM) -placement.offsetYDp else placement.offsetYDp).dp
    return Modifier
        .align(BiasAlignment(horizontal, vertical))
        .padding(horizontal = Dimens.playerEdgeInset)
        .offset(x = x, y = y)
}

private fun SubtitlePlacement.textAlign(): TextAlign = when (horizontal) {
    SubtitleHorizontalAnchor.START -> TextAlign.Start
    SubtitleHorizontalAnchor.CENTER -> TextAlign.Center
    SubtitleHorizontalAnchor.END -> TextAlign.End
}

/**
 * Styles every word according to the user's word-colour settings (SUB-5) and tags it so a tap can
 * name the word under the finger. Tagging is unconditional; the colours are what the setting turns off.
 */
internal fun styleWords(
    text: String,
    wordStates: Map<String, WordVisualState>,
    styles: WordStyleSettings,
): AnnotatedString {
    val colour = styles.enabled && wordStates.isNotEmpty()
    return AnnotatedString.Builder().apply {
        var cursor = 0
        Tokenizer.spans(text).forEach { span ->
            if (span.start > cursor) append(text.substring(cursor, span.start))
            val start = length
            append(text.substring(span.start, span.end))
            cursor = span.end
            if (!span.isWord) return@forEach
            addStringAnnotation(SUBTITLE_WORD_TAG, span.text, start, length)
            val style: WordStyle? = if (colour) {
                when (wordStates[span.text.lowercase()]) {
                    WordVisualState.MARKED -> styles.myWords
                    WordVisualState.ABOVE_LEVEL -> styles.unknownAboveLevel
                    WordVisualState.PHRASE -> styles.phrases
                    WordVisualState.KNOWN, WordVisualState.NONE, null -> null
                }
            } else {
                null
            }
            if (style != null) addStyle(style.toSpanStyle(), start, length)
        }
        if (cursor < text.length) append(text.substring(cursor))
    }.toAnnotatedString()
}

/**
 * The span for one styled word. Scale is relative (`em`), so a word stays in proportion to the line
 * it sits in whatever the layer's size is; alpha is applied to the colour only, never the background.
 */
internal fun WordStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = colorArgb?.let { Color(it).copy(alpha = alphaPercent / PERCENT) } ?: Color.Unspecified,
    background = backgroundArgb?.let { Color(it) } ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontSize = if (scalePercent == DEFAULT_WORD_SCALE_PERCENT) TextUnit.Unspecified else (scalePercent / PERCENT).em,
    textDecoration = decoration.toCompose(),
)

private const val DEFAULT_WORD_SCALE_PERCENT = 100
