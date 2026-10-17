package com.sublearn.feature.settings

import androidx.compose.ui.platform.LocalContext

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.common.TimeUtils
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.formatBytes
import com.sublearn.core.models.ProcessResources
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.AccuracyProfileToken
import org.koin.androidx.compose.koinViewModel

/**
 * The subtitle lab (D-87): one subtitle file, the player's text pipeline, and the numbers.
 *
 * Laid out top to bottom as the user works: pick a file, step through its blocks, choose the
 * profile and the pack, analyse, read the result, read the cost. The lab never plays a video and
 * never writes to My Words or the span cache; the only state it changes is the accuracy profile
 * and the active pack, which are the two things it exists to compare.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabSection(
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = koinViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val packs by viewModel.installedPacks.collectAsStateWithLifecycle()

    // A model that is being loaded must finish before the screen goes: the loaders hold native
    // memory, and leaving mid-load is how the earlier crash reports looked.
    BackHandler(enabled = ui.busy) {}

    val picker = rememberLauncherForActivityResult(

        OpenDocumentContract(context = LocalContext.current, mimeTypes = arrayOf("*/*")),

    ) { picked ->
        if (picked != null) viewModel.loadSubtitle(picked.uri, picked.name)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        // --- where the text comes from: a subtitle file, or text the user pastes (D-93)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            FilterChip(
                selected = ui.mode == LabInputMode.FILE,
                onClick = { viewModel.setMode(LabInputMode.FILE) },
                enabled = !ui.busy,
                label = { Text(stringResource(R.string.subtitle_file_name)) },
            )
            FilterChip(
                selected = ui.mode == LabInputMode.TEXT,
                onClick = { viewModel.setMode(LabInputMode.TEXT) },
                enabled = !ui.busy,
                label = { Text(stringResource(R.string.lab_mode_text)) },
            )
        }

        if (ui.mode == LabInputMode.FILE) {
            // --- the file
            SectionCard(stringResource(R.string.lab_title)) {
                if (ui.hasFile) {
                    Text(ui.fileName.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(R.string.lab_file_meta, ui.format.orEmpty(), ui.blockCount, ui.charset.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.lab_no_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.busy) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                    Spacer(Modifier.width(Dimens.xs))
                    Text(stringResource(R.string.lab_pick_subtitle))
                }
                if (ui.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(Dimens.spinnerSmall), strokeWidth = Dimens.spinnerStroke)
                        Spacer(Modifier.width(Dimens.sm))
                        Text(stringResource(R.string.lab_busy), style = MaterialTheme.typography.bodySmall)
                    }
                }
                ui.error?.let { message ->
                    Text(
                        text = stringResource(R.string.lab_error_parse, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (ui.hasFile) {
                // --- the block
                SectionCard(
                    stringResource(
                        R.string.lab_block_position,
                        ui.index + 1,
                        ui.blockCount,
                        TimeUtils.formatClock(ui.blockStartMs),
                        TimeUtils.formatClock(ui.blockEndMs),
                    ),
                ) {
                    Surface(
                        shape = RoundedCornerShape(Dimens.radiusSm),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(ui.blockText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Dimens.sm))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = viewModel::previous, enabled = ui.index > 0 && !ui.busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.lab_previous))
                        }
                        IconButton(onClick = viewModel::next, enabled = ui.index < ui.blockCount - 1 && !ui.busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.lab_next))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = viewModel::analyzeCurrent, enabled = !ui.busy) {
                            Text(stringResource(R.string.lab_analyze))
                        }
                    }
                    OutlinedButton(onClick = viewModel::benchmark, enabled = !ui.busy) {
                        Text(stringResource(R.string.lab_benchmark, LabViewModel.BENCHMARK_BLOCKS.coerceAtMost(ui.blockCount)))
                    }
                    ui.benchmark?.let { bench ->
                        Text(
                            text = stringResource(
                                R.string.lab_benchmark_result,
                                bench.blocks,
                                bench.totalMs,
                                "%.1f".format(bench.perBlockMs),
                                bench.slowestMs,
                                bench.spans,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            LabTextSection(
                text = ui.text,
                busy = ui.busy,
                onInput = viewModel::setTextInput,
                onAnalyze = viewModel::analyzeText,
                onToggleWord = viewModel::toggleTextWord,
                onSelectSpan = viewModel::selectSpan,
                onClear = viewModel::clearTextSelection,
            )
        }

        // --- the profile
        SectionCard(stringResource(R.string.lab_section_profile)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AccuracyProfileToken.entries.filter { it != AccuracyProfileToken.CUSTOM }.forEach { profile ->
                    FilterChip(
                        selected = settings.smartLearning.accuracyProfile == profile,
                        onClick = { viewModel.setAccuracyProfile(profile) },
                        enabled = !ui.busy,
                        label = { Text(stringResource(profile.labelRes())) },
                    )
                }
            }
        }

        // --- the packs
        SectionCard(stringResource(R.string.lab_section_packs)) {
            if (packs.isEmpty()) {
                Text(
                    text = stringResource(R.string.lab_no_packs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                packs.forEach { pack ->
                    FilterChip(
                        selected = pack.enabled,
                        onClick = { viewModel.activatePack(pack) },
                        enabled = !ui.busy,
                        label = { Text("${pack.titleEn} ${pack.version}") },
                    )
                }
            }
        }

        // --- the engine, after the first analysis
        ui.engine?.let { engine ->
            SectionCard(stringResource(R.string.lab_section_engine)) {
                Text(
                    text = stringResource(
                        R.string.lab_engine_profile,
                        stringResource(settings.smartLearning.accuracyProfile.labelRes()),
                        engine.packId,
                        engine.packVersion,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(if (engine.meetsProfile) R.string.lab_engine_meets else R.string.lab_engine_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (engine.meetsProfile) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = if (engine.runtimes.isEmpty()) {
                        stringResource(R.string.lab_engine_none)
                    } else {
                        stringResource(R.string.lab_engine_runtimes, engine.runtimes.joinToString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                engine.diagnostics.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- the result (file mode; the text mode shows its own result under its box)
        ui.result?.takeIf { ui.mode == LabInputMode.FILE }?.let { result ->
            SectionCard(stringResource(R.string.lab_section_result)) {
                Text(
                    text = stringResource(R.string.lab_timing, result.phraseMs, result.lookupMs, result.rankMs, result.totalMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.lab_spans_header, result.spans.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (result.spans.isEmpty()) {
                    Text(
                        text = stringResource(R.string.lab_spans_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.spans.forEach { span ->
                    Text(
                        text = stringResource(R.string.lab_span_line, span.headword, span.kind, span.confidencePercent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = span.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.lab_words_header, result.knownWords, result.unknownWords, result.rankedWords),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.lab_rank_limit_note, LabViewModel.RANK_LIMIT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result.words.forEach { word -> WordRow(word) }
            }
        }

        // --- the cost
        SectionCard(stringResource(R.string.lab_section_resources)) {
            ui.resources?.let { ResourceLines(it) }
            OutlinedButton(onClick = viewModel::refreshResources) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                Spacer(Modifier.width(Dimens.xs))
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
internal fun WordRow(word: LabWordRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(word.word, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(Dimens.sm))
            val meta = if (word.lemmaKey == null) {
                stringResource(R.string.lab_word_unknown)
            } else {
                listOfNotNull(
                    word.lemmaKey.takeIf { it != word.word.lowercase() },
                    word.pos,
                    word.cefr,
                    stringResource(R.string.lab_word_senses, word.senseCount),
                ).joinToString(" · ")
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = if (word.lemmaKey == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        word.topSenseFa?.let { sense ->
            Text(
                text = stringResource(R.string.lab_word_top_sense, sense, word.topSensePos.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ResourceLines(resources: ProcessResources) {
    Text(
        text = stringResource(R.string.lab_res_heap, formatBytes(resources.heapUsedBytes), formatBytes(resources.heapMaxBytes)),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.lab_res_native, formatBytes(resources.nativeHeapBytes)),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.lab_res_pss, formatBytes(resources.pssKb * 1024L)),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.lab_res_device, resources.deviceAvailableRamMb, resources.deviceTotalRamMb),
        style = MaterialTheme.typography.bodySmall,
    )
    if (resources.lowMemory) {
        Text(
            text = stringResource(R.string.lab_res_low),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
