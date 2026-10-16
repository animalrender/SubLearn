package com.sublearn.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.ai.ProfileAvailability
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.DownloadItemCard
import com.sublearn.core.designsystem.DownloadItemState
import com.sublearn.core.designsystem.SubLearnLinearProgress
import com.sublearn.core.designsystem.R
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.translate.TranslationModelState
import org.koin.androidx.compose.koinViewModel

/**
 * The one place where everything the app can download is downloaded.
 *
 * Four things live here, in the order a user meets them: the offline translation model, the
 * offline lexicon pack, the optional accuracy models and the accuracy profile that uses them.
 *
 * They used to be spread over three screens — "Library & Resources", "Models & Downloads" and
 * the translation settings — each with its own idea of what "installed" meant. The same model was
 * listed twice with two different sizes, and the translation model could be downloaded from three
 * places, two of which did not work. One screen, one state, one download button per item (D-81).
 */
@Composable
fun DownloadsSection(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val modelRows by viewModel.nlpModels.collectAsStateWithLifecycle()
    val translationState by viewModel.translationModelState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val profileAvailability by viewModel.profileAvailability.collectAsStateWithLifecycle()
    val recommendedProfile by viewModel.recommendedProfile.collectAsStateWithLifecycle()
    val deviceRamMb by viewModel.deviceRamMb.collectAsStateWithLifecycle()

    var showTranslationInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NlpModelRow?>(null) }

    val v2Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importV2Pack(picked.uri)
    }
    val v1Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importLegacyV1Pack(picked.uri)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Surface(
                shape = RoundedCornerShape(Dimens.radiusSm),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(Dimens.sm),
                )
            }
        }

        // --- 1. the offline translation model
        SectionCard(stringResource(R.string.downloads_translation_header)) {
            DownloadItemCard(
                name = stringResource(R.string.downloads_translation_name),
                description = stringResource(R.string.downloads_translation_desc),
                sizeBytes = viewModel.translationModelSize(),
                state = translationState.toDownloadItemState(),
                modifier = Modifier.fillMaxWidth(),
                onDownload = viewModel::downloadTranslationModel,
                onDelete = viewModel::removeTranslationModel,
                onRetry = viewModel::downloadTranslationModel,
                onInfo = { showTranslationInfo = true },
            )
        }

        // --- 2. the offline lexicon and dictionary packs
        SectionCard(stringResource(R.string.downloads_packs_header)) {
            Text(
                text = stringResource(R.string.downloads_packs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            viewModel.catalogEntries.forEach { entry ->
                CatalogEntryRow(
                    entry = entry,
                    downloadState = downloadStates[entry.id],
                    onDownload = { viewModel.downloadCatalogEntry(entry) },
                )
            }

            Spacer(Modifier.height(Dimens.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                TextButton(onClick = { v2Picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                    Spacer(Modifier.size(Dimens.xs))
                    Text(stringResource(R.string.library_import_v2_pack))
                }
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
        }

        // --- 3. the optional accuracy models
        SectionCard(stringResource(R.string.downloads_models_header)) {
            Text(
                text = stringResource(R.string.downloads_models_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (row in modelRows) {
                Spacer(Modifier.height(Dimens.sm))
                AccuracyModelCard(
                    row = row,
                    onInstall = { viewModel.installNlpModel(row.model.id) },
                    onDelete = { pendingDelete = row },
                )
            }
        }

        // --- 4. the accuracy profile the models are for
        SectionCard(stringResource(R.string.downloads_profile_header)) {
            Text(
                text = stringResource(R.string.downloads_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                AccuracyProfileToken.entries.filter { it != AccuracyProfileToken.CUSTOM }.forEach { profile ->
                    FilterChip(
                        selected = settings.smartLearning.accuracyProfile == profile,
                        onClick = { viewModel.setAccuracyProfile(profile) },
                        label = { Text(stringResource(profile.labelRes())) },
                    )
                }
            }
            val selected = settings.smartLearning.accuracyProfile
            val availability = profileAvailability[selected]
            Text(
                text = profileStatusText(availability, deviceRamMb),
                style = MaterialTheme.typography.labelSmall,
                color = if (availability is ProfileAvailability.Ready) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            Text(
                text = stringResource(R.string.downloads_profile_recommended, stringResource(recommendedProfile.labelRes())),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showTranslationInfo) {
        AlertDialog(
            onDismissRequest = { showTranslationInfo = false },
            title = { Text(stringResource(R.string.downloads_translation_name)) },
            text = { Text(stringResource(R.string.downloads_translation_info)) },
            confirmButton = {
                TextButton(onClick = { showTranslationInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    pendingDelete?.let { row ->
        val state = row.state as? NlpModelState.Installed
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_models_nlp_delete_title, row.model.name)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_models_nlp_delete_body,
                        megabytes(state?.bytesOnDisk ?: row.model.sizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNlpModel(row.model.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * One accuracy model.
 *
 * The description names the profile the model unlocks, which is the only thing that makes the
 * difference between `all-MiniLM-L6-v2` and `distilbert-base-uncased` meaningful to a user.
 */
@Composable
private fun AccuracyModelCard(
    row: NlpModelRow,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val runtimeAvailable = row.model.runtime.isAvailable
    val description = buildString {
        append(modelPurpose(row.model))
        append(" · ")
        append(modelRuntime(row.model.runtime))
    }

    DownloadItemCard(
        name = row.model.name,
        description = description,
        sizeBytes = row.model.sizeBytes,
        state = row.toDownloadItemState(runtimeAvailable),
        modifier = Modifier.fillMaxWidth(),
        onDownload = onInstall,
        onRetry = onInstall,
        onDelete = onDelete,
        progress = (row.state as? NlpModelState.Downloading)?.progress?.fraction ?: 0f,
        progressText = (row.state as? NlpModelState.Downloading)?.progress?.let { progress ->
            if (progress.fileCount > 1) {
                stringResource(
                    R.string.settings_models_nlp_progress,
                    megabytes(progress.bytesDownloaded),
                    megabytes(progress.totalBytes),
                )
            } else {
                null
            }
        },
        detail = (row.state as? NlpModelState.Installed)?.let {
            stringResource(R.string.settings_models_nlp_installed_detail, megabytes(it.bytesOnDisk))
        },
    )
}

/**
 * The profile this model belongs to, phrased as what it adds rather than what it is.
 *
 * The profile name comes from the same labels the chips use, so the card and the chip agree in
 * both languages.
 */
@Composable
private fun modelPurpose(model: NlpModel): String = when (model.id) {
    "emb-potion-base-8m" -> stringResource(
        R.string.settings_models_nlp_balancer,
        stringResource(R.string.profile_balanced),
    )

    "emb-minilm-l6-int8" -> stringResource(
        R.string.settings_models_nlp_precise,
        stringResource(R.string.profile_precise),
    )

    "wsd-distilbert-int8" -> stringResource(
        R.string.settings_models_nlp_advanced,
        stringResource(R.string.profile_advanced),
    )

    else -> stringResource(
        R.string.settings_models_nlp_smart,
        stringResource(R.string.profile_smart),
    )
}

@Composable
private fun modelRuntime(runtime: NlpModelRuntime): String = when (runtime) {
    NlpModelRuntime.NONE -> stringResource(R.string.settings_models_nlp_runtime_none)
    NlpModelRuntime.ONNX -> stringResource(R.string.settings_models_nlp_runtime_onnx)
    NlpModelRuntime.LITERT -> stringResource(R.string.settings_models_nlp_runtime_litert)
}

/**
 * The card state, with the two reasons a model may be listed but not offered made explicit: the
 * engine is not in this build, or the publisher requires the licence to be accepted first.
 */
@Composable
private fun NlpModelRow.toDownloadItemState(runtimeAvailable: Boolean): DownloadItemState = when (val current = state) {
    is NlpModelState.Available -> when {
        !runtimeAvailable -> DownloadItemState.UNAVAILABLE(
            stringResource(R.string.settings_models_nlp_runtime_missing, model.runtime.name),
        )

        model.requiresTermsAcceptance -> DownloadItemState.UNAVAILABLE(
            stringResource(R.string.settings_models_nlp_gated),
        )

        else -> DownloadItemState.AVAILABLE
    }

    is NlpModelState.Downloading -> DownloadItemState.DOWNLOADING
    is NlpModelState.Installed -> DownloadItemState.INSTALLED
    is NlpModelState.Error -> DownloadItemState.ERROR(current.message, retryable = runtimeAvailable)
}

/** The translation model's state, in the shared card's vocabulary. */
private fun TranslationModelState.toDownloadItemState(): DownloadItemState = when (this) {
    TranslationModelState.Unknown -> DownloadItemState.AVAILABLE
    TranslationModelState.NotInstalled -> DownloadItemState.AVAILABLE
    TranslationModelState.Downloading -> DownloadItemState.DOWNLOADING
    TranslationModelState.Installed -> DownloadItemState.INSTALLED
    is TranslationModelState.Failed -> DownloadItemState.ERROR(message)
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
                    text = stringResource(
                        R.string.downloads_pack_meta,
                        pack.kind,
                        pack.entryCount,
                        pack.sizeBytes / 1024,
                    ),
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
    var showDetails by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.nameEn, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(
                        R.string.downloads_catalog_meta,
                        entry.nameFa,
                        entry.sizeBytes / (1024 * 1024),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The ⓘ answers "what am I actually downloading" — version, hash, source — without
            // leaving the list.
            IconButton(onClick = { showDetails = true }) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.library_entry_details),
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.action_download))
            }
        }
        if (downloadState != null && downloadState.status == PackDownloadState.Status.DOWNLOADING) {
            SubLearnLinearProgress(
                progress = { downloadState.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(entry.nameEn) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                    Text(entry.nameFa, style = MaterialTheme.typography.bodyMedium)
                    entry.notes?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = stringResource(R.string.library_entry_version, entry.version),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = stringResource(R.string.library_entry_size, entry.sizeBytes / (1024 * 1024)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    entry.license?.takeIf { it.isNotBlank() }?.let { license ->
                        Text(
                            text = stringResource(R.string.library_entry_license, license),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = stringResource(R.string.library_entry_source, entry.source.host),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val hash = entry.sha256
                    if (hash.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.library_entry_no_hash),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.library_entry_hash, hash.take(16)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/** `12.3 MB`: one place decides how a size reads, so every card agrees. */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
