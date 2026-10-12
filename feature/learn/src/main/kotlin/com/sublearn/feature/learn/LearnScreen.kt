package com.sublearn.feature.learn

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import org.koin.androidx.compose.koinViewModel

/**
 * The Learn & Resource Library tab (LRN-5, D-51, D-52, D-60):
 * - First-run onboarding card (dismissible)
 * - Learning modes, CEFR level, popup count & lifetime
 * - Accuracy profile selector (`BASIC..SMART_EXPERIMENTAL`) with hardware benchmark
 * - Unified "Library & Resources" center: installed packs, import v2 / legacy v1 packs, and
 *   catalog v2 downloads with SHA-256 verification.
 */
@Composable
fun LearnScreen(
    onOpenWords: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LearnViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val levelState by viewModel.levelState.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val benchmarkReport by viewModel.benchmarkReport.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val learning = settings.learning
    val smart = settings.smartLearning

    val v2Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importV2Pack(picked.uri)
    }
    val v1Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importLegacyV1Pack(picked.uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        if (!smart.completedOnboarding && installedPacks.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.onboarding_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        TextButton(onClick = { v2Picker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(R.string.library_import_v2_pack))
                        }
                        TextButton(onClick = viewModel::dismissOnboarding) {
                            Text(stringResource(R.string.onboarding_dismiss))
                        }
                    }
                }
            }
        }

        statusMessage?.takeIf { it.isNotBlank() }?.let { msg ->
            Surface(
                shape = RoundedCornerShape(Dimens.radiusSm),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(Dimens.sm),
                )
            }
        }

        Text(stringResource(R.string.settings_learning_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            LearningMode.entries.forEach { mode ->
                FilterChip(
                    selected = learning.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }
        Text(
            text = stringResource(
                when (learning.mode) {
                    LearningMode.ENTERTAINMENT -> R.string.learn_mode_entertainment_hint
                    LearningMode.LEARNING -> R.string.learn_mode_learning_hint
                    LearningMode.OFF -> R.string.learn_mode_off_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Dimens.lg)) {
                Text(stringResource(R.string.settings_level), style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier
                        .padding(top = Dimens.sm)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    CefrLevel.entries.filter { it != CefrLevel.UNKNOWN }.forEach { level ->
                        FilterChip(
                            selected = learning.manualLevel == level,
                            onClick = { viewModel.setLevel(level) },
                            label = { Text(level.name) },
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    stringResource(R.string.learn_level_source_hint, levelState.importedWords),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_level_import)) }
            }
        }

        // Accuracy Profile & Benchmark (D-52)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(stringResource(R.string.library_section_models), style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    AccuracyProfileToken.entries.filter { it != AccuracyProfileToken.CUSTOM }.forEach { profile ->
                        FilterChip(
                            selected = smart.accuracyProfile == profile,
                            onClick = { viewModel.setAccuracyProfile(profile) },
                            label = { Text(profile.name) },
                        )
                    }
                }
                benchmarkReport?.let { rep ->
                    Text(
                        text = "${rep.recommendedProfile.name} · ${rep.availableHeapMb} MB RAM · ${rep.latencyMs} ms" +
                            (rep.downgradedReason?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Unified Library & Resources Center (D-60)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.library_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    TextButton(onClick = { v2Picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                        Spacer(Modifier.size(Dimens.xs))
                        Text(stringResource(R.string.library_import_v2_pack))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    TextButton(onClick = { v1Picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                        Spacer(Modifier.size(Dimens.xs))
                        Text(stringResource(R.string.library_import_legacy_v1))
                    }
                }

                if (installedPacks.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.library_section_installed), style = MaterialTheme.typography.titleSmall)
                    installedPacks.forEach { pack ->
                        InstalledPackRow(
                            pack = pack,
                            onActivate = { viewModel.activatePack(pack) },
                            onVerify = { viewModel.verifyPack(pack) },
                            onDelete = { viewModel.deletePack(pack) },
                        )
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.library_section_lexicons), style = MaterialTheme.typography.titleSmall)
                viewModel.catalogEntries.forEach { entry ->
                    CatalogEntryRow(
                        entry = entry,
                        downloadState = downloadStates[entry.id],
                        onDownload = { viewModel.downloadCatalogEntry(entry) },
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(Dimens.lg)) {
                Text(stringResource(R.string.settings_popup_count), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = learning.maxPopupCards.toFloat(),
                    onValueChange = { viewModel.setPopupCount(it.toInt()) },
                    valueRange = 1f..10f,
                )
                Text("${learning.maxPopupCards}", style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.settings_popup_lifetime), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = learning.popupLifetimeMs.toFloat(),
                    onValueChange = { viewModel.setPopupLifetime(it.toLong()) },
                    valueRange = 1_500f..15_000f,
                    steps = 8,
                )
                Text("${learning.popupLifetimeMs / 1000}s", style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(Dimens.sm))
            Text(stringResource(R.string.menu_my_words), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenWords) { Text(stringResource(R.string.words_title)) }
        }
    }
}

@Composable
private fun InstalledPackRow(
    pack: InstalledPackEntity,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pack.titleEn, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${pack.kind} · ${pack.entryCount} entries · ${pack.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pack.enabled) {
                Text(
                    text = stringResource(R.string.library_pack_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onActivate) {
                    Text(stringResource(R.string.library_pack_activate))
                }
            }
            IconButton(onClick = onVerify) {
                Icon(Icons.Default.Verified, contentDescription = stringResource(R.string.library_pack_verify))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.library_pack_delete))
            }
        }
    }
}

@Composable
private fun CatalogEntryRow(
    entry: ModelCatalogEntry,
    downloadState: PackDownloadState?,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.nameEn, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${entry.nameFa} · ${entry.sizeBytes / (1024 * 1024)} MB · ${entry.profile.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.translate_download))
            }
        }
        if (downloadState != null && downloadState.status == PackDownloadState.Status.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { downloadState.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The offline Dictionary search tab (D-55, D-58, D-60):
 * Searches EN→FA and FA→EN headwords/expressions in the active lexicon pack and lets the user
 * bookmark any entry directly into My Words.
 */
@Composable
fun DictionaryScreen(
    modifier: Modifier = Modifier,
    viewModel: LearnViewModel = koinViewModel(),
) {
    val query by viewModel.dictQuery.collectAsStateWithLifecycle()
    val direction by viewModel.dictDirection.collectAsStateWithLifecycle()
    val entry by viewModel.dictEntry.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = direction == "en-fa",
                onClick = { viewModel.setDictDirection("en-fa") },
                label = { Text(stringResource(R.string.dict_dir_en_fa)) },
            )
            FilterChip(
                selected = direction == "fa-en",
                onClick = { viewModel.setDictDirection("fa-en") },
                label = { Text(stringResource(R.string.dict_dir_fa_en)) },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::searchDictionary,
            placeholder = { Text(stringResource(R.string.dict_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (installedPacks.isEmpty()) {
            Text(
                text = stringResource(R.string.dict_no_pack_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val currentEntry = entry
        if (currentEntry == null) {
            if (query.isBlank()) {
                Text(
                    text = stringResource(R.string.dict_empty_state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentEntry.word,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        currentEntry.phonetic?.takeIf { it.isNotBlank() }?.let { ipa ->
                            Text(
                                text = if (ipa.startsWith("/")) ipa else "/$ipa/",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(Dimens.sm))
                        }
                        IconButton(onClick = { viewModel.bookmarkDictionaryEntry(currentEntry) }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.dict_bookmark_entry))
                        }
                    }

                    currentEntry.senses.forEach { (pos, meanings) ->
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                            Text(
                                text = pos,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = meanings.joinToString("، "),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    if (currentEntry.synonyms.isNotEmpty()) {
                        Text(
                            text = "Synonyms: ${currentEntry.synonyms.take(10).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (currentEntry.examples.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.popup_examples_title), style = MaterialTheme.typography.labelMedium)
                        currentEntry.examples.take(4).forEach { ex ->
                            Text("• $ex", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun LearningMode.labelRes(): Int = when (this) {
    LearningMode.ENTERTAINMENT -> R.string.settings_mode_entertainment
    LearningMode.LEARNING -> R.string.settings_mode_learning
    LearningMode.OFF -> R.string.settings_mode_off
}
