package com.sublearn.feature.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import com.sublearn.core.designsystem.Palette
import com.sublearn.core.designsystem.SubtitleBackdrop
import com.sublearn.core.designsystem.toCompose
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.nlp.CueStreamPhraseAnalyzer
import com.sublearn.core.nlp.PhraseKind
import com.sublearn.core.nlp.PhraseSpan
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.TextDecorationToken
import com.sublearn.core.settings.WordStyle
import com.sublearn.core.settings.WordStyleSettings
import com.sublearn.core.subtitles.Tokenizer
import com.sublearn.core.subtitles.TrackRole

/** Percent to fraction, shared by scale and transparency. */
private const val PERCENT = 100f

internal const val SUBTITLE_UNDERLINE_TAG = "sub_underline"
private val DASH_PATTERN = floatArrayOf(8f, 5f)
private val DOT_PATTERN = floatArrayOf(2f, 4f)

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
            val lines = layer.lines.ifEmpty {
                layer.text?.takeIf { it.isNotBlank() }?.let {
                    listOf(
                        SubtitleLineUi(
                            key = "current",
                            text = it,
                            block = layer.block,
                            spans = ui.lineSpans["current"].orEmpty(),
                        ),
                    )
                }.orEmpty()
            }
            if (layer.visible && lines.isNotEmpty()) {
                SubtitlePlate(role = role, lines = lines, ui = ui, registry = registry)
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
    lines: List<SubtitleLineUi>,
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
    val activeKeys = lines.map { it.key }
    DisposableEffect(role, registry) {
        onDispose { registry.forgetSubtitle(role) }
    }
    val outline = when {
        ui.layoutMode -> Modifier.border(Dimens.strokeWidth, colors.overlayOutline, RoundedCornerShape(spec.cornerRadiusDp.dp))
        isLearning -> Modifier.border(Dimens.strokeWidth, Color(Palette.PROUD_OVERLAY_BORDER), RoundedCornerShape(spec.cornerRadiusDp.dp))
        else -> Modifier
    }
    DisposableEffect(role, activeKeys, registry) {
        onDispose { activeKeys.forEach { registry.forgetSubtitleText(role, it) } }
    }
    SubtitleBackdrop(
        modifier = placed(placement)
            .then(outline)
            .onGloballyPositioned { coordinates -> registry.putSubtitlePlate(role, coordinates) },
        alpha = (1f - layer.transparencyPercent / PERCENT).coerceIn(0f, 1f),
        colorArgb = spec.backgroundColorArgb ?: if (isLearning) Palette.PROUD_OVERLAY_BG else null,
        cornerRadiusDp = spec.cornerRadiusDp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            lines.forEach { line ->
                key(line.key) {
                    val phraseSpans = if (isLearning) {
                        line.spans.ifEmpty { ui.lineSpans[line.key].orEmpty() }
                    } else {
                        emptyList()
                    }
                    val annotated = remember(
                        line.text,
                        ui.wordStates,
                        phraseSpans,
                        ui.savedPhraseKeys,
                        settings.wordStyles,
                    ) {
                        styleWords(
                            text = line.text,
                            wordStates = ui.wordStates,
                            styles = settings.wordStyles,
                            phraseSpans = phraseSpans,
                            savedPhraseKeys = ui.savedPhraseKeys,
                        )
                    }
                    val refs = remember { SubtitleTextRefs() }
                    Text(
                        text = annotated,
                        style = style.copy(textAlign = placement.textAlign()),
                        color = spec.colorArgb?.let { Color(it) } ?: Color(Palette.PROUD_WORD_NORMAL),
                        maxLines = settings.subtitles.maxLinesPerLayer,
                        onTextLayout = { measured ->
                            refs.layout = measured
                            refs.coordinates?.let { registry.putSubtitleText(role, line.key, it, measured) }
                        },
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                val layout = refs.layout ?: return@drawWithContent
                                val textLen = layout.layoutInput.text.length
                                if (textLen == 0) return@drawWithContent
                                val strokePx = Dimens.underlineStroke.toPx()
                                val annotations = layout.layoutInput.text.getStringAnnotations(
                                    SUBTITLE_UNDERLINE_TAG,
                                    0,
                                    textLen,
                                )
                                for (ann in annotations) {
                                    val parts = ann.item.split(':')
                                    if (parts.size != 2) continue
                                    val mode = parts[0]
                                    val colorArgb = parts[1].toLongOrNull() ?: continue
                                    val effect = when (mode) {
                                        TextDecorationToken.DASHED_UNDERLINE.name -> PathEffect.dashPathEffect(DASH_PATTERN, 0f)
                                        TextDecorationToken.DOTTED_UNDERLINE.name -> PathEffect.dashPathEffect(DOT_PATTERN, 0f)
                                        else -> null
                                    } ?: continue
                                    val startIdx = ann.start.coerceIn(0, textLen - 1)
                                    val endExclusive = ann.end.coerceIn(startIdx + 1, textLen)
                                    val firstLine = layout.getLineForOffset(startIdx)
                                    val lastLine = layout.getLineForOffset(endExclusive - 1)
                                    for (lineIdx in firstLine..lastLine) {
                                        val segStart = maxOf(startIdx, layout.getLineStart(lineIdx))
                                        val lineLastChar = (layout.getLineEnd(lineIdx, visibleEnd = true) - 1).coerceAtLeast(segStart)
                                        val segEnd = minOf(endExclusive - 1, lineLastChar)
                                        val startBox = layout.getBoundingBox(segStart)
                                        val endBox = layout.getBoundingBox(segEnd)
                                        val left = minOf(startBox.left, endBox.left)
                                        val right = maxOf(startBox.right, endBox.right)
                                        val y = maxOf(startBox.bottom, endBox.bottom) - strokePx * 0.5f
                                        if (right > left) {
                                            drawLine(
                                                color = Color(colorArgb),
                                                start = Offset(left, y),
                                                end = Offset(right, y),
                                                strokeWidth = strokePx,
                                                cap = StrokeCap.Round,
                                                pathEffect = effect,
                                            )
                                        }
                                    }
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                refs.coordinates = coordinates
                                refs.layout?.let { registry.putSubtitleText(role, line.key, coordinates, it) }
                            },
                    )
                }
            }
        }
    }
}

/** The text measurement and coordinates can arrive in either order during layout. */
private class SubtitleTextRefs {
    var coordinates: LayoutCoordinates? = null
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
 * Styles every word and detected multi-word expression according to the user's word-colour settings
 * (SUB-5, D-48, D-50) and tags it so a tap can name the word under the finger.
 */
internal fun styleWords(
    text: String,
    wordStates: Map<String, WordVisualState>,
    styles: WordStyleSettings,
    phraseSpans: List<PhraseSpan> = emptyList(),
    savedPhraseKeys: Set<String> = emptySet(),
): AnnotatedString {
    val colour = styles.enabled && (wordStates.isNotEmpty() || phraseSpans.isNotEmpty())
    val streamTokens = if (phraseSpans.isNotEmpty()) CueStreamPhraseAnalyzer.tokenizeText(text) else emptyList()

    return AnnotatedString.Builder().apply {
        var cursor = 0
        Tokenizer.spans(text).forEach { span ->
            if (span.start > cursor) append(text.substring(cursor, span.start))
            val start = length
            append(text.substring(span.start, span.end))
            cursor = span.end
            if (!span.isWord) return@forEach
            addStringAnnotation(SUBTITLE_WORD_TAG, span.text, start, length)

            if (!colour) return@forEach

            val matchingPhrase = findMatchingPhraseSpan(span.start, span.end, phraseSpans, streamTokens)
            val resolved: Pair<WordStyle, Long?>? = if (matchingPhrase != null) {
                val isPhraseSaved = matchingPhrase.lemmaKey.lowercase() in savedPhraseKeys ||
                    matchingPhrase.headword.lowercase() in savedPhraseKeys
                if (isPhraseSaved && !styles.savedPhrasesKeepTypeColor) {
                    styles.myWords to (styles.myWords.colorArgb ?: Palette.PROUD_WORD_SAVED)
                } else {
                    when (matchingPhrase.kind) {
                        PhraseKind.PHRASAL -> styles.phrasalVerbs to Palette.PROUD_PHRASAL_UNDERLINE
                        PhraseKind.IDIOM -> styles.idioms to Palette.PROUD_IDIOM_UNDERLINE
                        PhraseKind.COLLOCATION -> styles.collocations to Palette.PROUD_COLLOC_UNDERLINE
                    }
                }
            } else {
                when (wordStates[span.text.lowercase()]) {
                    WordVisualState.MARKED -> styles.myWords to (styles.myWords.colorArgb ?: Palette.PROUD_WORD_SAVED)
                    WordVisualState.ABOVE_LEVEL -> styles.unknownAboveLevel to styles.unknownAboveLevel.colorArgb
                    WordVisualState.PHRASAL -> styles.phrasalVerbs to Palette.PROUD_PHRASAL_UNDERLINE
                    WordVisualState.IDIOM -> styles.idioms to Palette.PROUD_IDIOM_UNDERLINE
                    WordVisualState.COLLOCATION -> styles.collocations to Palette.PROUD_COLLOC_UNDERLINE
                    WordVisualState.KNOWN, WordVisualState.NONE, null -> null
                }
            }

            if (resolved != null) {
                val (wordStyle, underlineColorArgb) = resolved
                val usesCanvasUnderline = wordStyle.decoration == TextDecorationToken.DASHED_UNDERLINE ||
                    wordStyle.decoration == TextDecorationToken.DOTTED_UNDERLINE
                addStyle(wordStyle.toSpanStyle(omitCustomUnderline = usesCanvasUnderline), start, length)
                if (usesCanvasUnderline && underlineColorArgb != null) {
                    addStringAnnotation(
                        SUBTITLE_UNDERLINE_TAG,
                        "${wordStyle.decoration.name}:$underlineColorArgb",
                        start,
                        length,
                    )
                }
            }
        }
        if (cursor < text.length) append(text.substring(cursor))
    }.toAnnotatedString()
}

private fun findMatchingPhraseSpan(
    charStart: Int,
    charEnd: Int,
    phraseSpans: List<PhraseSpan>,
    streamTokens: List<com.sublearn.core.nlp.TokenInfo>,
): PhraseSpan? {
    if (phraseSpans.isEmpty()) return null
    for (phrase in phraseSpans) {
        if (charStart < phrase.startChar || charEnd > phrase.endChar) continue
        if (phrase.separable && phrase.tokens.size == 2 && streamTokens.isNotEmpty()) {
            val matchedTokenRanges = phrase.tokens.mapNotNull { idx -> streamTokens.getOrNull(idx) }
            val hitsMatchedPart = matchedTokenRanges.any { tok ->
                maxOf(charStart, tok.startChar) < minOf(charEnd, tok.endChar)
            }
            if (hitsMatchedPart) return phrase
        } else {
            return phrase
        }
    }
    return null
}

/**
 * The span for one styled word. Scale is relative (`em`), so a word stays in proportion to the line
 * it sits in whatever the layer's size is; alpha is applied to the colour only, never the background.
 */
internal fun WordStyle.toSpanStyle(omitCustomUnderline: Boolean = false): SpanStyle = SpanStyle(
    color = colorArgb?.let { Color(it).copy(alpha = alphaPercent / PERCENT) } ?: Color.Unspecified,
    background = backgroundArgb?.let { Color(it) } ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    fontSize = if (scalePercent == DEFAULT_WORD_SCALE_PERCENT) TextUnit.Unspecified else (scalePercent / PERCENT).em,
    textDecoration = if (omitCustomUnderline) null else decoration.toCompose(),
)

private const val DEFAULT_WORD_SCALE_PERCENT = 100
