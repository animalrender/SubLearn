package com.sublearn.feature.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.nlp.PhraseSpan

/**
 * The free-text mode of the subtitle lab (D-93): paste a word, a phrase or sentences, read them the
 * way the flashcard player shows a subtitle, tap the words to select them, and read what the
 * selection means below. Ranking and lookups are the file mode's, so a pasted word and the same
 * word in a subtitle get the same answer.
 */
@Composable
fun LabTextSection(
    text: LabTextUi,
    busy: Boolean,
    onInput: (String) -> Unit,
    onAnalyze: () -> Unit,
    onToggleWord: (Int) -> Unit,
    onSelectSpan: (Int) -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(stringResource(R.string.lab_text_header)) {
        Text(
            text = stringResource(R.string.lab_text_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text.input,
            onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = 3,
            maxLines = 6,
            isError = text.tooLong,
            placeholder = { Text(stringResource(R.string.lab_text_hint)) },
            supportingText = {
                Text(stringResource(R.string.lab_text_count, text.input.length, LabTextSelection.MAX_CHARS))
            },
        )
        if (text.tooLong) {
            Text(
                text = stringResource(R.string.lab_text_too_long, LabTextSelection.MAX_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        text.error?.let { message ->
            Text(
                text = stringResource(R.string.lab_error_parse, message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAnalyze,
            enabled = text.input.isNotBlank() && !busy,
        ) {
            Text(stringResource(R.string.lab_text_analyze))
        }

        text.analysis?.let { analysis ->
            if (analysis.words.isEmpty()) {
                Text(
                    text = stringResource(R.string.lab_text_no_words),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AnalysedText(analysis, text.selected, onToggleWord, onClear)
                SelectionOutcome(LabTextSelection.resolve(analysis, text.selected))
            }
            ExpressionList(analysis.text, analysis.spans, onSelectSpan)
        }
    }
}

/**
 * The text as it reads in the flashcard player: one paragraph, selected words in bold on a tinted
 * background, words that belong to a found phrase underlined. A tap is mapped to the word under it.
 */
@Composable
private fun AnalysedText(
    analysis: LabTextAnalysis,
    selected: Set<Int>,
    onToggleWord: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val covered = remember(analysis.spans, analysis.words) {
        LabTextSelection.coveredWords(analysis.spans, analysis.words)
    }
    val selectedBackground = MaterialTheme.colorScheme.primaryContainer
    val selectedText = MaterialTheme.colorScheme.onPrimaryContainer
    val annotated = remember(analysis, selected, covered, selectedBackground, selectedText) {
        buildAnnotatedString {
            var cursor = 0
            analysis.words.forEach { word ->
                if (word.start > cursor) append(analysis.text.substring(cursor, word.start))
                val style = when {
                    word.index in selected -> SpanStyle(
                        background = selectedBackground,
                        color = selectedText,
                        fontWeight = FontWeight.Bold,
                    )

                    word.index in covered -> SpanStyle(textDecoration = TextDecoration.Underline)
                    else -> SpanStyle()
                }
                withStyle(style) { append(analysis.text.substring(word.start, word.end)) }
                cursor = word.end
            }
            if (cursor < analysis.text.length) append(analysis.text.substring(cursor))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge,
            onTextLayout = { layout = it },
            modifier = Modifier
                .padding(Dimens.sm)
                .pointerInput(analysis.words) {
                    detectTapGestures { position ->
                        val current = layout ?: return@detectTapGestures
                        val offset = current.getOffsetForPosition(position)
                        analysis.words.firstOrNull { offset >= it.start && offset < it.end }
                            ?.let { onToggleWord(it.index) }
                    }
                },
        )
    }
    Text(
        text = stringResource(R.string.lab_text_tap_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (selected.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.lab_text_selected, selected.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.lab_text_clear)) }
        }
    }
}

/** What the selection means: the phrase it forms, or each selected word on its own. */
@Composable
private fun SelectionOutcome(result: LabSelectionResult) {
    when (result) {
        LabSelectionResult.Empty -> Unit

        is LabSelectionResult.Phrase -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.lab_text_phrase_header),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.lab_span_line,
                    result.span.headword,
                    result.span.kind.name,
                    (result.span.confidence * 100).toInt().coerceIn(0, 100),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val info = result.info
            if (info == null) {
                Text(
                    text = stringResource(R.string.lab_text_phrase_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                info.senses.forEach { sense -> SenseLine(sense.glossFa, sense.pos) }
            }
        }

        is LabSelectionResult.Words -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.lab_text_words_header),
                style = MaterialTheme.typography.titleSmall,
            )
            if (result.partial.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.lab_text_partial,
                        result.partial.joinToString { it.headword },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (result.words.size > 1) {
                Text(
                    text = stringResource(R.string.lab_text_no_phrase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result.words.forEachIndexed { position, word ->
                val info = result.infos[position]
                WordRow(
                    LabWordRow(
                        word = word.text,
                        lemmaKey = info?.key,
                        cefr = info?.cefr,
                        pos = info?.pos,
                        senseCount = info?.senses?.size ?: 0,
                        topSenseFa = info?.topSense?.glossFa,
                        topSensePos = info?.topSense?.pos,
                    ),
                )
            }
        }
    }
}

/** Every phrase the analyser found, each with a button that selects exactly its words. */
@Composable
private fun ExpressionList(text: String, spans: List<PhraseSpan>, onSelectSpan: (Int) -> Unit) {
    HorizontalDivider()
    Text(
        text = stringResource(R.string.lab_text_expressions_header, spans.size),
        style = MaterialTheme.typography.titleSmall,
    )
    if (spans.isEmpty()) {
        Text(
            text = stringResource(R.string.lab_text_expressions_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    spans.forEachIndexed { index, span ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.lab_span_line,
                        span.headword,
                        span.kind.name,
                        (span.confidence * 100).toInt().coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = text.substring(
                        span.startChar.coerceIn(0, text.length),
                        span.endChar.coerceIn(0, text.length),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onSelectSpan(index) }) {
                Text(stringResource(R.string.lab_text_select_expression))
            }
        }
    }
}

@Composable
private fun SenseLine(gloss: String, pos: String?) {
    Text(
        text = if (pos.isNullOrBlank()) gloss else "$gloss ($pos)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
