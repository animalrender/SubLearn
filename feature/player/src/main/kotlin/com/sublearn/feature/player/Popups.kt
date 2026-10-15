package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.Palette
import com.sublearn.core.designsystem.WordCardTokens
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.badgeColorArgb
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.nlp.PhraseKind
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole

/**
 * The ProudVocab-inspired translation card (LRN-1, D-49, D-58, D-59).
 *
 * - Top section: tapped word (or line/block), base lemma, IPA phonetic, CEFR badge, source chips,
 *   POS-grouped Persian meanings, examples, and bookmark star.
 * - Bottom section (when a phrasal verb / idiom / collocation was detected on the tapped token):
 *   detected expression header, colored kind badge, phrase Persian meanings, examples, and a
 *   separate bookmark star so the learner can save the word, the expression, or both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PopupLayer(
    popup: PopupUi?,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onToggleMark: () -> Unit,
    onDownloadModel: () -> Unit,
    onAskAi: (String) -> Unit,
    modifier: Modifier = Modifier,
    registry: PlayerHitRegistry? = null,
    onTogglePhraseMark: () -> Unit = {},
) {
    val reduce = LocalReduceMotion.current
    val lastShown = remember { mutableStateOf(popup) }
    LaunchedEffect(popup) { if (popup != null) lastShown.value = popup }
    AnimatedVisibility(
        visible = popup != null,
        enter = Motion.popupEnter(reduce),
        exit = Motion.popupExit(reduce),
        modifier = modifier.fillMaxSize(),
    ) {
        val current = popup ?: lastShown.value ?: return@AnimatedVisibility
        val spec = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.NATIVE)
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .blocksGestures(registry)
                    .widthIn(min = Dimens.proudPopupMinWidth, max = Dimens.cardMaxWidth)
                    .padding(Dimens.lg),
                shape = RoundedCornerShape(Dimens.proudCardRadius),
                color = Color(Palette.PROUD_CARD_BG),
                border = BorderStroke(Dimens.strokeWidth, Color(Palette.PROUD_CARD_BORDER)),
                tonalElevation = Dimens.cardElevation,
                shadowElevation = spec.shadowElevationDp.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(Dimens.lg)
                        .heightIn(max = Dimens.cardMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    // §7: no card header and no close button. The card is anchored to the word the
                    // learner touched, so the level badge and the sources sit on the word row and a
                    // tap anywhere outside dismisses it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        current.level?.let { level -> LevelBadge(level) }
                        if (current.sourcesUsed.isNotEmpty()) {
                            Spacer(Modifier.size(Dimens.xs))
                            current.sourcesUsed.forEach { src ->
                                PopupSourceChip(src)
                                Spacer(Modifier.size(Dimens.xxs))
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                    ) {
                        Text(
                            text = current.sourceText,
                            style = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.LEARNING)
                                .toTextStyle(LocalAppFontScale.current),
                            color = Color(Palette.PROUD_WORD_NORMAL),
                            maxLines = 6,
                        )
                        val lemma = current.lemma
                        if (!lemma.isNullOrBlank() && !lemma.equals(current.sourceText, ignoreCase = true)) {
                            Text(
                                text = "→ $lemma",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        current.phonetic?.takeIf { it.isNotBlank() }?.let { ipa ->
                            Text(
                                text = if (ipa.startsWith("/")) ipa else "/$ipa/",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (current.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                            CircularProgressIndicator(Modifier.size(Dimens.spinnerSmall), strokeWidth = Dimens.spinnerStroke)
                            Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        current.translation?.let { translated ->
                            Text(
                                text = translated,
                                style = spec.toTextStyle(LocalAppFontScale.current),
                                color = Color(Palette.PROUD_WORD_NORMAL),
                            )
                        }
                        if (current.posGlosses.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                                current.posGlosses.take(4).forEach { (pos, terms) ->
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                                        verticalArrangement = Arrangement.spacedBy(Dimens.xxs),
                                    ) {
                                        if (!pos.isNullOrBlank()) {
                                            PopupPosChip(pos)
                                        }
                                        Text(
                                            text = terms.take(5).joinToString("، "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (current.examples.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                                current.examples.take(2).forEach { (enEx, faEx) ->
                                    Text(
                                        text = "• $enEx",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (faEx.isNotBlank()) {
                                        Text(
                                            text = "  $faEx",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        current.contextTranslation?.let { context ->
                            Text(
                                text = context,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (current.isPendingOnline) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                            ) {
                                CircularProgressIndicator(Modifier.size(Dimens.spinnerSmall), strokeWidth = Dimens.spinnerStroke)
                                Text(
                                    text = stringResource(R.string.popup_online_loading),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        current.error?.let { error ->
                            Column {
                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                if (current.needsModelDownload) {
                                    TextButton(onClick = onDownloadModel) { Text(stringResource(R.string.translate_download)) }
                                }
                            }
                        }
                        if (current.translation == null && current.error == null && !current.busy) {
                            Text(
                                stringResource(R.string.translate_offline_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SaveWordButton(marked = current.marked, onClick = onToggleMark)
                        // "more" continues into the AI sheet rather than cramming every meaning
                        // onto the card (§7).
                        IconButton(
                            onClick = { onAskAi(current.sourceText) },
                            modifier = Modifier.size(WordCardTokens.SAVE_TOUCH_TARGET),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.ai_explain),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = clock(current.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // --- Bottom Section: Detected Phrasal Verb / Idiom / Collocation (D-49) ---
                    current.phraseSection?.let { phrase ->
                        HorizontalDivider(color = Color(Palette.PROUD_OVERLAY_BORDER))
                        PopupPhraseCardSection(
                            phrase = phrase,
                            settings = settings,
                            onTogglePhraseMark = onTogglePhraseMark,
                            onAskAi = onAskAi,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The card's save control (§7): a rounded 28dp chip with a 40dp touch target, drawn from
 * [WordCardTokens] so the popup, My Words and the settings previews cannot drift apart.
 */
@Composable
private fun SaveWordButton(marked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(WordCardTokens.SAVE_TOUCH_TARGET)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(WordCardTokens.SAVE_BUTTON_RADIUS),
            color = Color(WordCardTokens.SAVE_BUTTON_BG),
            border = BorderStroke(Dimens.strokeWidth, Color(WordCardTokens.SAVE_BUTTON_BORDER)),
        ) {
            Box(
                modifier = Modifier.size(WordCardTokens.SAVE_BUTTON_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (marked) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(if (marked) R.string.word_remove else R.string.word_add),
                    tint = if (marked) Color(Palette.PROUD_WORD_SAVED) else Color(WordCardTokens.SAVE_ICON_COLOR),
                    modifier = Modifier.size(WordCardTokens.SAVE_ICON_SIZE),
                )
            }
        }
    }
}

@Composable
private fun PopupPhraseCardSection(
    phrase: PopupPhraseSection,
    settings: AppSettings,
    onTogglePhraseMark: () -> Unit,
    onAskAi: (String) -> Unit,
) {
    val kindColor = when (phrase.kind) {
        PhraseKind.PHRASAL -> Color(Palette.PROUD_PHRASAL_TEXT)
        PhraseKind.IDIOM -> Color(Palette.PROUD_IDIOM_TEXT)
        PhraseKind.COLLOCATION -> Color(Palette.PROUD_COLLOC_TEXT)
    }
    val kindLabel = stringResource(
        when (phrase.kind) {
            PhraseKind.PHRASAL -> R.string.popup_phrase_kind_phrasal
            PhraseKind.IDIOM -> R.string.popup_phrase_kind_idiom
            PhraseKind.COLLOCATION -> R.string.popup_phrase_kind_collocation
        },
    )
    val nativeSpec = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.NATIVE)

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(Dimens.radiusSm),
                color = kindColor.copy(alpha = 0.18f),
            ) {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = kindColor,
                    modifier = Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
                )
            }
            phrase.level?.let { lvl ->
                Spacer(Modifier.size(Dimens.xs))
                LevelBadge(lvl)
            }
            Spacer(Modifier.weight(1f))
            SaveWordButton(marked = phrase.marked, onClick = onTogglePhraseMark)
            IconButton(
                onClick = { onAskAi(phrase.headword) },
                modifier = Modifier.size(WordCardTokens.SAVE_TOUCH_TARGET),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.ai_explain), tint = kindColor)
            }
        }

        Text(
            text = phrase.headword,
            style = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.LEARNING)
                .toTextStyle(LocalAppFontScale.current),
            color = kindColor,
        )

        phrase.translation?.takeIf { it.isNotBlank() }?.let { trans ->
            Text(
                text = trans,
                style = nativeSpec.toTextStyle(LocalAppFontScale.current),
                color = Color(Palette.PROUD_WORD_NORMAL),
            )
        }

        // §7: up to MAX_PHRASE_ROWS losing overlaps, each on its own row with its own type colour.
        phrase.alternatives.take(WordCardTokens.MAX_PHRASE_ROWS).forEach { alternative ->
            HorizontalDivider(color = Color(WordCardTokens.PHRASE_DIVIDER_COLOR))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val altColor = when (alternative.kind) {
                    PhraseKind.PHRASAL -> Color(WordCardTokens.COLOR_PHRASAL)
                    PhraseKind.IDIOM -> Color(WordCardTokens.COLOR_IDIOM)
                    PhraseKind.COLLOCATION -> Color(WordCardTokens.COLOR_COLLOC)
                }
                Text(
                    text = alternative.headword,
                    style = MaterialTheme.typography.labelLarge,
                    color = altColor,
                    modifier = Modifier.weight(1f, fill = false),
                )
                alternative.translation?.takeIf { it.isNotBlank() }?.let { altTrans ->
                    Spacer(Modifier.size(Dimens.xs))
                    Text(
                        text = altTrans,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (phrase.examples.isNotEmpty()) {
            phrase.examples.take(1).forEach { (enEx, faEx) ->
                Text(
                    text = "• $enEx",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (faEx.isNotBlank()) {
                    Text(
                        text = "  $faEx",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PopupSourceChip(source: String) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = Dimens.xs, vertical = Dimens.xxs),
        )
    }
}

@Composable
private fun PopupPosChip(pos: String) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Text(
            text = pos,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Dimens.xs, vertical = Dimens.xxs),
        )
    }
}

@Composable
fun LevelBadge(level: String, modifier: Modifier = Modifier) {
    val parsed = CefrLevel.entries.firstOrNull { it.name == level } ?: CefrLevel.UNKNOWN
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = Color(parsed.badgeColorArgb()).copy(alpha = 0.2f),
    ) {
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            color = Color(parsed.badgeColorArgb()),
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
        )
    }
}

/** The answer sheet for the AI helper (AI-2, AI-3): the sections the prompt asked for, in order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnswerSheet(
    ui: PlayerUi,
    onDismiss: () -> Unit,
    onAskAgain: () -> Unit,
    onResume: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ai = ui.ai
    val answerText = ui.ai.answer?.text ?: ""
    val sections = remember(answerText) { com.sublearn.core.ai.AiAnswerParser.parse(answerText) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.lg, vertical = Dimens.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_answer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ai.pausedForAnswer) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.ai_resume)) }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            ai.question?.let { question ->
                Text(question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (ai.state) {
                AiState.BUSY -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    CircularProgressIndicator(Modifier.size(Dimens.inlineIcon), strokeWidth = Dimens.spinnerStroke)
                    Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                    if (ui.playback.isPlaying.not()) {
                        Text(stringResource(R.string.ai_pause_hint), style = MaterialTheme.typography.labelSmall)
                    }
                }

                AiState.ERROR -> Column {
                    Text(
                        stringResource(R.string.ai_error, ai.error ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        TextButton(onClick = onAskAgain) { Text(stringResource(R.string.ai_ask_again)) }
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_category_ai)) }
                    }
                }

                AiState.ANSWER -> {
                    Section(R.string.ai_section_meaning, sections.meaning)
                    Section(R.string.ai_section_why, sections.whyUsed)
                    Section(R.string.ai_section_synonyms, sections.synonyms)
                    Section(R.string.ai_section_elsewhere, sections.elsewhere)
                    Section(R.string.word_details, sections.extra)
                    ai.answer?.let { answer ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${answer.providerId} · ${answer.model}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onCopy(answer.text) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.ai_copy))
                            }
                            IconButton(onClick = onAskAgain) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ai_ask_again))
                            }
                        }
                    }
                }

                AiState.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun Section(titleRes: Int, body: String?) {
    if (body.isNullOrBlank()) return
    Column {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Dimens.xs))
    }
}
