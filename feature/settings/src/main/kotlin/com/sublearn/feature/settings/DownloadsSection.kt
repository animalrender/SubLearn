package com.sublearn.feature.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.ai.ModelFileLayout
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.ai.ProfileAvailability
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.DownloadItemCard
import com.sublearn.core.designsystem.DownloadItemState
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.formatBytes
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
 * offline lexicon pack, the optional accuracy models and the accuracy profile that uses them —
 * and, at the end, the door to the subtitle lab where they can be tried (D-87).
 *
 * They used to be spread over three screens — "Library & Resources", "Models & Downloads" and
 * the translation settings — each with its own idea of what "installed" meant. The same model was
 * listed twice with two different sizes, and the translation model could be downloaded from three
 * places, two of which did not work. One screen, one state, one download button per item (D-81).
 *
 * Every downloadable item is a [DownloadItemCard]: the catalog packs used to be a different,
 * denser row with a cloud icon for download and no way to stop, which is the "words all over the
 * place" the owner reported. One card, one layout, one stop button (D-87).
 *
 * @param onOpenLab navigate to the subtitle lab section
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsSection(
    modifier: Modifier = Modifier,
    onOpenLab: () -> Unit = {},
    viewModel: DownloadsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val importingEntries by viewModel.importingEntries.collectAsStateWithLifecycle()
    val modelRows by viewModel.nlpModels.collectAsStateWithLifecycle()
    val translationState by viewModel.translationModelState.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    val profileAvailability by viewModel.profileAvailability.collectAsStateWithLifecycle()
    val recommendedProfile by viewModel.recommendedProfile.collectAsStateWithLifecycle()
    val deviceRamMb by viewModel.deviceRamMb.collectAsStateWithLifecycle()

    var showTranslationInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NlpModelRow?>(null) }
    var pendingPackDelete by remember { mutableStateOf<InstalledPackEntity?>(null) }
    var catalogInfo by remember { mutableStateOf<ModelCatalogEntry?>(null) }
    var modelInfo by remember { mutableStateOf<NlpModel?>(null) }

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
            StatusBanner(message = message, onDismiss = viewModel::clearStatus)
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
                // ML Kit reports no progress and cannot be stopped part-way (#18): an indeterminate
                // bar, and no stop button because tapping one would do nothing.
                progress = null,
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
                val installed = viewModel.installedPackFor(entry, installedPacks)
                CatalogEntryCard(
                    entry = entry,
                    downloadState = downloadStates[entry.id],
                    importing = entry.id in importingEntries,
                    installed = installed,
                    onDownload = { viewModel.downloadCatalogEntry(entry) },
                    onCancel = { viewModel.cancelCatalogDownload(entry) },
                    onInfo = { catalogInfo = entry },
                    onActivate = { installed?.let(viewModel::activatePack) },
                    onVerify = { installed?.let(viewModel::verifyPack) },
                    onDelete = { pendingPackDelete = installed },
                )
            }

            // Wrapping, not a Row: the two import labels are long in both languages and a Row cut
            // the second one in half on a 360 dp phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { v2Picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                    Spacer(Modifier.width(Dimens.xs))
                    Text(stringResource(R.string.library_import_v2_pack))
                }
                TextButton(onClick = { v1Picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                    Spacer(Modifier.width(Dimens.xs))
                    Text(stringResource(R.string.library_import_legacy_v1))
                }
            }

            // Imported packs that no catalog entry describes (a user's own file) are listed on
            // their own; the catalog packs appear above, on their card.
            val extraPacks = installedPacks.filter { pack ->
                viewModel.catalogEntries.none { viewModel.installedPackFor(it, installedPacks)?.packId == pack.packId }
            }
            if (extraPacks.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.library_section_installed), style = MaterialTheme.typography.titleSmall)
                extraPacks.forEach { pack ->
                    InstalledPackCard(
                        pack = pack,
                        onActivate = { viewModel.activatePack(pack) },
                        onVerify = { viewModel.verifyPack(pack) },
                        onDelete = { pendingPackDelete = pack },
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
                AccuracyModelCard(
                    row = row,
                    testing = (selfTest as? SelfTestUi.Running)?.modelId == row.model.id,
                    onInstall = { viewModel.installNlpModel(row.model.id) },
                    onCancel = { viewModel.cancelNlpModel(row.model.id) },
                    onTest = { viewModel.testNlpModel(row.model.id) },
                    onDelete = { pendingDelete = row },
                    onInfo = { modelInfo = row.model },
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
            // Chips wrap into rows; a horizontal scroller hid the last two profiles behind the
            // screen edge with nothing to say they were there.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                modifier = Modifier.fillMaxWidth(),
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
                style = MaterialTheme.typography.bodySmall,
                color = if (availability is ProfileAvailability.Ready) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
            Text(
                text = stringResource(R.string.downloads_profile_recommended, stringResource(recommendedProfile.labelRes())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- 5. the subtitle lab
        SectionCard(stringResource(R.string.downloads_lab_header)) {
            Text(
                text = stringResource(R.string.downloads_lab_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenLab) {
                Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(Dimens.inlineIcon))
                Spacer(Modifier.width(Dimens.xs))
                Text(stringResource(R.string.downloads_lab_open))
            }
        }
    }

    if (showTranslationInfo) {
        AlertDialog(
            onDismissRequest = { showTranslationInfo = false },
            title = { Text(stringResource(R.string.downloads_translation_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Text(stringResource(R.string.downloads_translation_info))
                    Text(
                        text = stringResource(R.string.downloads_translation_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTranslationInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    catalogInfo?.let { entry ->
        CatalogEntryInfoDialog(entry = entry, onDismiss = { catalogInfo = null })
    }

    modelInfo?.let { model ->
        NlpModelInfoDialog(
            model = model,
            layout = viewModel.modelLayout(model.id),
            onDismiss = { modelInfo = null },
        )
    }

    selfTest?.let { test -> SelfTestDialog(test = test, onDismiss = viewModel::dismissSelfTest) }

    pendingPackDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingPackDelete = null },
            title = { Text(stringResource(R.string.settings_models_nlp_delete_title, pack.titleEn)) },
            text = {
                Text(stringResource(R.string.settings_models_nlp_delete_body, formatBytes(pack.sizeBytes)))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePack(pack)
                        pendingPackDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPackDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
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
                        formatBytes(state?.bytesOnDisk ?: row.model.sizeBytes),
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

/** The one-line status under the title, dismissible so an old message does not sit there all day. */
@Composable
private fun StatusBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimens.sm, top = Dimens.sm, bottom = Dimens.sm),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(Dimens.inlineIcon),
                )
            }
        }
    }
}

/**
 * One catalog pack, on the shared card.
 *
 * The downloader's state and the installed-pack table are two sources that have to agree on one
 * card: a pack that is installed is "installed" whatever the downloader last said, a download in
 * flight wins over everything, and the import that follows a finished download is "installing".
 */
@Composable
private fun CatalogEntryCard(
    entry: ModelCatalogEntry,
    downloadState: PackDownloadState?,
    importing: Boolean,
    installed: InstalledPackEntity?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInfo: () -> Unit,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = when {
        downloadState?.status == PackDownloadState.Status.DOWNLOADING -> DownloadItemState.DOWNLOADING
        downloadState?.status == PackDownloadState.Status.VERIFYING -> DownloadItemState.VERIFYING
        importing -> DownloadItemState.INSTALLING
        downloadState?.status == PackDownloadState.Status.PAUSED -> DownloadItemState.PAUSED
        downloadState?.status == PackDownloadState.Status.FAILED ->
            DownloadItemState.ERROR(downloadState.error.orEmpty())
        installed != null -> DownloadItemState.INSTALLED
        else -> DownloadItemState.AVAILABLE
    }
    val progressText = downloadState?.takeIf { it.totalBytes > 0L }?.let {
        stringResource(R.string.settings_models_nlp_progress, formatBytes(it.downloadedBytes), formatBytes(it.totalBytes))
    }
    DownloadItemCard(
        name = entry.nameEn,
        description = entry.nameFa,
        sizeBytes = entry.sizeBytes,
        state = state,
        modifier = Modifier.fillMaxWidth(),
        onDownload = onDownload,
        onCancel = onCancel,
        onDelete = onDelete,
        onRetry = onDownload,
        onInfo = onInfo,
        progress = downloadState?.fraction ?: 0f,
        progressText = progressText,
        detail = installed?.let { pack ->
            stringResource(R.string.downloads_pack_installed_detail, pack.entryCount, formatBytes(pack.sizeBytes))
        },
        actions = if (installed != null) {
            { PackActions(pack = installed, onActivate = onActivate, onVerify = onVerify) }
        } else {
            null
        },
    )
}

/** An imported pack the catalog does not list: the same card, with what the import recorded. */
@Composable
private fun InstalledPackCard(
    pack: InstalledPackEntity,
    onActivate: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    DownloadItemCard(
        name = pack.titleEn,
        description = pack.titleFa.ifBlank { pack.kind },
        sizeBytes = pack.sizeBytes,
        state = DownloadItemState.INSTALLED,
        modifier = Modifier.fillMaxWidth(),
        onDelete = onDelete,
        onInfo = { showDetails = true },
        detail = stringResource(R.string.downloads_pack_installed_detail, pack.entryCount, formatBytes(pack.sizeBytes)),
        actions = { PackActions(pack = pack, onActivate = onActivate, onVerify = onVerify) },
    )
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(pack.titleEn) },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xxs)) {
                        Text(stringResource(R.string.library_entry_version, pack.version), style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = stringResource(R.string.downloads_pack_meta, pack.kind, pack.entryCount, pack.sizeBytes / 1024),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.library_entry_source, pack.localPath),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        HashLine(pack.sha256)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/** "Active" or "Set active", and "Verify", for an installed pack. */
@Composable
private fun PackActions(pack: InstalledPackEntity, onActivate: () -> Unit, onVerify: () -> Unit) {
    if (pack.enabled) {
        OutlinedButton(onClick = {}, enabled = false) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
            Spacer(Modifier.width(Dimens.xs))
            Text(stringResource(R.string.library_pack_active))
        }
    } else {
        OutlinedButton(onClick = onActivate) { Text(stringResource(R.string.library_pack_activate)) }
    }
    OutlinedButton(onClick = onVerify) { Text(stringResource(R.string.library_pack_verify)) }
}

/**
 * Everything about a catalog pack, including the exact URL it is fetched from.
 *
 * The URL is shown whole and is selectable: the owner asked to be able to see precisely where
 * each pack comes from, and a host name is not that.
 */
@Composable
private fun CatalogEntryInfoDialog(entry: ModelCatalogEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.nameEn) },
        text = {
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(entry.nameFa, style = MaterialTheme.typography.bodyMedium)
                    entry.notes?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.library_entry_version, entry.version), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.library_entry_size, entry.sizeBytes / (1024 * 1024)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    entry.license?.takeIf { it.isNotBlank() }?.let { license ->
                        Text(stringResource(R.string.library_entry_license, license), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = stringResource(R.string.downloads_source_url),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    UrlLine(entry.source.url)
                    HashLine(entry.sha256)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * Everything about an accuracy model: every file it needs, each with its exact URL and hash.
 *
 * The runtime opens several files (a session, a tokenizer, a vocabulary), and a model that fails
 * to load is debugged by checking those files one by one, so each is listed.
 */
@Composable
private fun NlpModelInfoDialog(
    model: NlpModel,
    layout: ModelFileLayout?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.name) },
        text = {
            SelectionContainer {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(modelPurpose(model), style = MaterialTheme.typography.bodyMedium)
                    Text(modelRuntime(model.runtime), style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(R.string.settings_models_nlp_installed_detail, formatBytes(model.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (layout != null) {
                        Text(
                            text = stringResource(R.string.downloads_source_files),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        layout.assets.forEach { asset ->
                            Text(
                                text = stringResource(R.string.downloads_source_file_line, asset.name, formatBytes(asset.sizeBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            UrlLine(asset.url)
                            HashLine(asset.sha256)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun UrlLine(url: String) {
    Text(
        text = url,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HashLine(sha256: String?) {
    if (sha256.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.downloads_sha_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            text = stringResource(R.string.downloads_sha_full, sha256),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** The self-test dialog: a spinner while the model loads, then the verdict and its numbers (D-86). */
@Composable
private fun SelfTestDialog(test: SelfTestUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_test_title, test.modelName)) },
        text = {
            when (test) {
                is SelfTestUi.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.inlineIcon + Dimens.xxs),
                        strokeWidth = Dimens.spinnerStroke,
                    )
                    Spacer(Modifier.width(Dimens.sm))
                    Text(stringResource(R.string.downloads_test_running, test.modelName))
                }

                is SelfTestUi.Done -> SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        val report = test.report
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (report.ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (report.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(Dimens.inlineIcon),
                            )
                            Spacer(Modifier.width(Dimens.xs))
                            Text(
                                text = stringResource(if (report.ok) R.string.downloads_test_ok else R.string.downloads_test_failed),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (report.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            )
                        }
                        report.runtimeName?.let { runtime ->
                            Text(
                                text = stringResource(R.string.downloads_test_runtime, runtime),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.downloads_test_timing,
                                report.loadMs,
                                report.inferenceMs,
                                formatBytes(report.heapDeltaBytes.coerceAtLeast(0L)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(report.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            if (test is SelfTestUi.Done) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

/**
 * One accuracy model.
 *
 * The description names the profile the model unlocks, which is the only thing that makes the
 * difference between `all-MiniLM-L6-v2` and `distilbert-base-uncased` meaningful to a user. The
 * "Test" action opens the installed model and runs one probe through it (D-86).
 */
@Composable
private fun AccuracyModelCard(
    row: NlpModelRow,
    testing: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
) {
    val runtimeAvailable = row.model.runtime.isAvailable
    val description = buildString {
        append(modelPurpose(row.model))
        append(" · ")
        append(modelRuntime(row.model.runtime))
    }
    val downloading = row.state as? NlpModelState.Downloading

    DownloadItemCard(
        name = row.model.name,
        description = description,
        sizeBytes = row.model.sizeBytes,
        state = row.toDownloadItemState(runtimeAvailable),
        modifier = Modifier.fillMaxWidth(),
        onDownload = onInstall,
        onCancel = onCancel,
        onRetry = onInstall,
        onDelete = onDelete,
        onInfo = onInfo,
        progress = downloading?.progress?.fraction,
        progressText = downloading?.progress?.let { progress ->
            val bytes = stringResource(
                R.string.settings_models_nlp_progress,
                formatBytes(progress.bytesDownloaded),
                formatBytes(progress.totalBytes),
            )
            if (progress.fileCount > 1) "$bytes · ${progress.fileIndex + 1}/${progress.fileCount} ${progress.fileName}" else bytes
        },
        detail = (row.state as? NlpModelState.Installed)?.let {
            stringResource(R.string.settings_models_nlp_installed_detail, formatBytes(it.bytesOnDisk))
        },
        actions = {
            OutlinedButton(onClick = onTest, enabled = !testing) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimens.spinnerSmall), strokeWidth = Dimens.spinnerStroke)
                } else {
                    Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
                }
                Spacer(Modifier.width(Dimens.xs))
                Text(stringResource(R.string.action_test))
            }
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
