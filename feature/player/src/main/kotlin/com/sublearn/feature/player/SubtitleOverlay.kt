package com.sublearn.feature.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.SubtitleBackdrop
import com.sublearn.core.designsystem.toCompose
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.TextDecorationToken
import com.sublearn.core.settings.WordStyle
import com.sublearn.core.settings.WordStyleSettings
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.Tokenizer
import com.sublearn.core.subtitles.TrackRole

/**
 * Both subtitle layers, drawn by SubLearn rather than by the player so that every word can be
 * touched (PLY-7, SUB-4).
 *
 * Hit testing uses the real [TextLayoutResult] of the rendered text: the touch position becomes a
 * character offset, which becomes the token covering it. Keeping a parallel "word grid" would have
 * been a second source of truth about where the text is, so there is none.
 */
@Composable
fun SubtitleLayerStack(
    ui: PlayerUi,
    onLayerTap: (TrackRole, PopupKind, String?, SubtitleBlock?) -> Unit,
    onMove: (TrackRole, SubtitlePlacement) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = when (ui.settings.subtitles.layer(TrackRole.LEARNING).stackDirection) {
        LayerStackDirection.ABOVE -> listOf(TrackRole.LEARNING, TrackRole.TRANSLATION)
        LayerStackDirection.BELOW -> listOf(TrackRole.TRANSLATION, TrackRole.LEARNING)
    }
    Box(modifier = modifier.fillMaxSize()) {
        roles.forEach { role ->
            val layer = ui.layer(role)
            val text = layer.text
            if (!layer.visible || text.isNullOrBlank()) return@forEach
            SubtitleLayerView(
                role = role,
                text = text,
                block = layer.block,
                settings = ui.settings,
                wordStates = ui.wordStates,
                layoutMode = ui.layoutMode,
                tapEnabled = layer.canStepBySubtitle || ui.settings.learning.tapCountsForLineAndBlock,
                onTaps = { count, word ->
                    val kind = when {
                        word != null -> PopupKind.WORD
                        count >= 3 -> PopupKind.BLOCK
                        else -> PopupKind.LINE
                    }
                    onLayerTap(role, kind, word, layer.block)
                },
                onDragBy = { dx, dy -> onMove(role, layer.placement.movedBy(dx, dy)) },
            )
        }
    }
}

/**
 * Nudge a placement by a drag. Both deltas are already in dp (the caller divides by the density it
 * reads from the pointer input scope). The range keeps a layer reachable from every anchor: the
 * offsets are relative to the anchored position, so a larger screen just has more room to move.
 */
internal fun SubtitlePlacement.movedBy(dx: Float, dy: Float): SubtitlePlacement = copy(
    offsetXDp = (offsetXDp + dx).coerceIn(-600f, 600f),
    offsetYDp = (offsetYDp + dy).coerceIn(-900f, 900f),
)

@Composable
private fun SubtitleLayerView(
    role: TrackRole,
    text: String,
    block: SubtitleBlock?,
    settings: AppSettings,
    wordStates: Map<String, WordVisualState>,
    layoutMode: Boolean,
    tapEnabled: Boolean,
    onTaps: (Int, String?) -> Unit,
    onDragBy: (Float, Float) -> Unit,
) {
    val layer = settings.subtitleLayer(role)
    val surface = if (role == TrackRole.LEARNING) FontSurface.SUBTITLE_LEARNING else FontSurface.SUBTITLE_TRANSLATION
    val spec = settings.fontFor(surface, if (role == TrackRole.LEARNING) SubtitleLayerRole.LEARNING else SubtitleLayerRole.NATIVE)
    val baseScale = LocalAppFontScale.current * (layer.scalePercent / 100f)
    val style: TextStyle = remember(spec, baseScale) { spec.toTextStyle(baseScale) }
    val annotated = remember(text, wordStates, settings.wordStyles) {
        styleWords(text, wordStates, settings.wordStyles)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    fun wordAt(position: Offset): String? = layout?.wordUnder(position)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        SubtitleBackdrop(
            alpha = (1f - layer.transparencyPercent / 100f).coerceIn(0f, 1f),
            colorArgb = spec.backgroundColorArgb,
            cornerRadiusDp = spec.cornerRadiusDp,
            modifier = Modifier
                .subtitlePlacement(layer.placement)
                .pointerInput(layoutMode, tapEnabled) {
                    if (layoutMode) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDragBy(drag.x / density, drag.y / density)
                        }

                    } else if (tapEnabled) {
                        detectMultiTap(
                            windowMs = settings.learning.multiTapWindowMs,
                            onTaps = { count, position -> onTaps(count, layout?.wordUnder(position)) },
                        )
                    }
                },
        ) {
            Text(
                text = annotated,
                style = style.copy(
                    textAlign = when (layer.placement.horizontal) {
                        SubtitleHorizontalAnchor.START -> TextAlign.Start
                        SubtitleHorizontalAnchor.END -> TextAlign.End
                        SubtitleHorizontalAnchor.CENTER -> TextAlign.Center
                    },
                ),
                maxLines = settings.subtitles.maxLinesPerLayer,
                color = Color.White,
                onTextLayout = { layout = it },
            )
        }
        if (settings.subtitles.showLanguageBadge && block != null) {
            Text(
                text = block.cues.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private fun TextLayoutResult.wordUnder(position: Offset): String? {
    val offset = runCatching { getOffsetForPosition(position) }.getOrNull() ?: return null
    return layoutInput.text.getStringAnnotations(WORD_TAG, offset, offset).firstOrNull()?.item
}

private const val WORD_TAG = "sublearn-word"

/** Anchors a layer box inside the video area. */
/**
 * Places the plate in the video area from its anchors plus the user's offsets.
 *
 * `Box` alignment is the only sane source for "top / centre / bottom", so the drag handler writes
 * back both the anchor it landed in and the remaining pixel offset.
 */
private fun Modifier.subtitlePlacement(placement: SubtitlePlacement): Modifier {
    val vertical = when (placement.vertical) {
        SubtitleVerticalAnchor.TOP -> Alignment.TopStart
        SubtitleVerticalAnchor.CENTER -> Alignment.CenterStart
        SubtitleVerticalAnchor.BOTTOM -> Alignment.BottomStart
    }
    val horizontalPadding = when (placement.horizontal) {
        SubtitleHorizontalAnchor.START -> maxOf(0f, placement.offsetXDp)
        SubtitleHorizontalAnchor.END -> 0f
        SubtitleHorizontalAnchor.CENTER -> 0f
    }
    val endPadding = if (placement.horizontal == SubtitleHorizontalAnchor.END) maxOf(0f, placement.offsetXDp) else 0f
    val verticalPadding = when (placement.vertical) {
        SubtitleVerticalAnchor.TOP -> maxOf(0f, placement.offsetYDp)
        SubtitleVerticalAnchor.CENTER -> 0f
        SubtitleVerticalAnchor.BOTTOM -> maxOf(0f, -placement.offsetYDp)
    }
    return align(vertical)
        .padding(start = horizontalPadding.dp, end = endPadding.dp, top = verticalPadding.dp, bottom = verticalPadding.dp)
        .fillMaxWidth()
}

/**
 * Styles every word according to the user's word-colour settings (SUB-5) and tags it so a tap can
 * name the word it landed on.
 */
internal fun styleWords(text: String, wordStates: Map<String, WordVisualState>, styles: WordStyleSettings): AnnotatedString {
    if (!styles.enabled || wordStates.isEmpty()) return AnnotatedString(text)
    return AnnotatedString.Builder().apply {
        Tokenizer.spans(text).forEach { span ->
            val start = length
            append(text.substring(span.start, span.end))
            addStringAnnotation(WORD_TAG, span.text, start, length)
            val state = if (span.isWord) wordStates[span.text.lowercase()] else null
            val style = when (state) {
                WordVisualState.MARKED -> styles.myWords
                WordVisualState.ABOVE_LEVEL -> styles.unknownAboveLevel
                WordVisualState.PHRASE -> styles.phrases
                WordVisualState.KNOWN, WordVisualState.NONE, null -> null
            }
            if (style != null) addStyle(style.toSpanStyle(), start, length)
        }
    }.toAnnotatedString()
}

internal fun WordStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = colorArgb?.let { Color(it) },
    background = backgroundArgb?.let { Color(it) },
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = null,
    textDecoration = decoration.toCompose(),
)
