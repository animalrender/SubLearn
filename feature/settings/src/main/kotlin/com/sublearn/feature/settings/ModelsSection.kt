package com.sublearn.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.ai.NlpModel
import com.sublearn.core.ai.NlpModelRuntime
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.DownloadItemCard
import com.sublearn.core.designsystem.DownloadItemState
import com.sublearn.core.designsystem.R
import com.sublearn.core.translate.MlKitModelInstaller

/**
 * Models & Downloads (§3.2): every downloadable artifact in one place, using the shared
 * [DownloadItemCard] so a pack, a model and a profile all look and behave the same.
 *
 * Three groups, in the order a user needs them: the translation model, which is what makes offline
 * translation work; the optional accuracy models, which each unlock one profile; and the footer,
 * which explains what removal does.
 *
 * A model whose engine is not part of this build is shown but not offered for download: the honest
 * sentence ("this model cannot run here") is more useful than a button that would spend half a
 * gigabyte to load nothing.
 */
@Composable
fun ModelsSection(viewModel: SettingsViewModel) {
    val mlKitState by viewModel.mlKitState.collectAsStateWithLifecycle()
    val modelRows by viewModel.nlpModels.collectAsStateWithLifecycle()
    var showMlKitInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NlpModelRow?>(null) }

    SectionCard(stringResource(R.string.settings_models_installed_header)) {
        DownloadItemCard(
            name = stringResource(R.string.settings_models_mlkit_name),
            description = stringResource(R.string.settings_models_mlkit_desc),
            sizeBytes = MlKitModelInstaller.EN_FA_METADATA.downloadSize,
            state = mlKitState.toDownloadItemState(),
            modifier = Modifier.fillMaxWidth(),
            onDownload = viewModel::installMlKitModel,
            onCancel = viewModel::deleteMlKitModel,
            onDelete = viewModel::deleteMlKitModel,
            onRetry = viewModel::installMlKitModel,
            onInfo = { showMlKitInfo = true },
            progress = (mlKitState as? MlKitModelInstaller.InstallState.Downloading)?.let { state ->
                val total = state.totalBytes.takeIf { it > 0 } ?: MlKitModelInstaller.EN_FA_METADATA.downloadSize
                if (total > 0) (state.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            } ?: 0f,
            progressText = (mlKitState as? MlKitModelInstaller.InstallState.Downloading)?.let { state ->
                val total = state.totalBytes.takeIf { it > 0 } ?: MlKitModelInstaller.EN_FA_METADATA.downloadSize
                stringResource(
                    R.string.settings_models_nlp_progress,
                    megabytes(state.bytesDownloaded),
                    megabytes(total),
                )
            },
        )
    }

    Spacer(Modifier.height(Dimens.md))

    SectionCard(stringResource(R.string.settings_models_nlp_header)) {
        Text(
            text = stringResource(R.string.settings_models_nlp_desc),
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

    Spacer(Modifier.height(Dimens.sm))

    Text(
        text = stringResource(R.string.settings_models_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showMlKitInfo) {
        AlertDialog(
            onDismissRequest = { showMlKitInfo = false },
            title = { Text(stringResource(R.string.settings_models_mlkit_name)) },
            text = { Text(stringResource(R.string.settings_models_mlkit_info)) },
            confirmButton = {
                TextButton(onClick = { showMlKitInfo = false }) {
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
        append(" \u00b7 ")
        append(modelRuntime(row.model.runtime))
    }

    DownloadItemCard(
        name = row.model.name,
        description = description,
        sizeBytes = row.model.sizeBytes,
        state = row.toDownloadItemState(runtimeAvailable),
        modifier = Modifier.fillMaxWidth(),
        // A download is only offered for a model that could actually run afterwards.
        onDownload = onInstall,
        onRetry = onInstall,
        onDelete = onDelete,
        progress = (row.state as? NlpModelState.Downloading)?.progress?.fraction ?: 0f,
        progressText = (row.state as? NlpModelState.Downloading)?.progress?.let { progress ->
            if (progress.fileCount > 1) {
                stringResource(R.string.settings_models_nlp_progress, megabytes(progress.bytesDownloaded), megabytes(progress.totalBytes))
            } else {
                null
            }
        },
        detail = (row.state as? NlpModelState.Installed)?.let {
            stringResource(R.string.settings_models_nlp_installed_detail, megabytes(it.bytesOnDisk))
        },
    )
}

/** The profile this model belongs to, phrased as what it adds rather than what it is. */
@Composable
private fun modelPurpose(model: NlpModel): String = when (model.id) {
    "emb-potion-base-8m" -> stringResource(R.string.settings_models_nlp_balancer)
    "emb-minilm-l6-int8" -> stringResource(R.string.settings_models_nlp_precise)
    "wsd-distilbert-int8" -> stringResource(R.string.settings_models_nlp_advanced)
    else -> stringResource(R.string.settings_models_nlp_smart)
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

/** Map the installer's own state to the shared card state. */
private fun MlKitModelInstaller.InstallState.toDownloadItemState(): DownloadItemState = when (this) {
    is MlKitModelInstaller.InstallState.NotInstalled -> DownloadItemState.AVAILABLE
    is MlKitModelInstaller.InstallState.Downloading -> DownloadItemState.DOWNLOADING
    is MlKitModelInstaller.InstallState.Verifying -> DownloadItemState.VERIFYING
    is MlKitModelInstaller.InstallState.Installing -> DownloadItemState.INSTALLING
    is MlKitModelInstaller.InstallState.Installed -> DownloadItemState.INSTALLED
    is MlKitModelInstaller.InstallState.Error -> DownloadItemState.ERROR(message, retryable)
}

/** `12.3 MB`: one place decides how a size reads, so every card agrees. */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
