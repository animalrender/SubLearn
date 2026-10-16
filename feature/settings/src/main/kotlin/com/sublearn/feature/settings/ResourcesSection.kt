package com.sublearn.feature.settings

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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.sublearn.core.designsystem.SubLearnLinearProgress
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.models.ModelCatalogEntry
import com.sublearn.core.models.PackDownloadState
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.ai.NlpModelCatalog
import com.sublearn.core.ai.ProfileTier
import com.sublearn.core.settings.AccuracyProfileToken
import org.koin.androidx.compose.koinViewModel

/** Pack imports, accuracy profiles and catalog downloads belong to Settings, not the future Learn tab. */
@Composable
fun ResourcesSection(
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResourcesViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val benchmarkReport by viewModel.benchmarkReport.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val smart = settings.smartLearning

    val v2Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importV2Pack(picked.uri)
    }
    val v1Picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("*/*"))) { picked ->
        if (picked != null) viewModel.importLegacyV1Pack(picked.uri)
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
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

        ActionRow(
            title = stringResource(R.string.settings_category_models),
            subtitle = stringResource(R.string.settings_hint_models),
            onClick = onOpenModels,
            showArrow = true,
        )

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
                // §5.1: say what the selected profile needs before the user switches to it. The
                // catalog is the same data the settings downloads screen uses, so the number here
                // is the size of the download that screen offers.
                val tier = ProfileTier.fromToken(smart.accuracyProfile.name)
                val neededModels = NlpModelCatalog.modelsFor(tier)
                if (neededModels.isNotEmpty()) {
                    val downloadMb = (NlpModelCatalog.downloadBytesFor(tier) + 1_048_575L) / 1_048_576L
                    Text(
                        text = stringResource(R.string.profile_needs_models, neededModels.size, downloadMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!NlpModelCatalog.isRunnableInThisBuild(tier)) {
                    Text(
                        text = stringResource(R.string.profile_runtime_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                    text = "${entry.nameFa} · ${entry.sizeBytes / (1024 * 1024)} MB · ${entry.profile.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // §6.7: the ⓘ answers "what am I actually downloading" — licence, version, hash,
            // runtime and RAM floor — without leaving the list.
            IconButton(onClick = { showDetails = true }) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.library_entry_details),
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.translate_download))
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
                        text = stringResource(
                            R.string.library_entry_size,
                            entry.sizeBytes / (1024 * 1024),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = stringResource(R.string.library_entry_runtime, entry.runtime.name),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (entry.minRamMb > 0) {
                        Text(
                            text = stringResource(R.string.library_entry_ram, entry.minRamMb),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = stringResource(R.string.library_entry_profile, entry.profile.name),
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
