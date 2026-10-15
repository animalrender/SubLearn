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
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.DownloadItemCard
import com.sublearn.core.designsystem.DownloadItemState
import com.sublearn.core.designsystem.R
import com.sublearn.core.translate.MlKitModelInstaller

/**
 * Models & Downloads (§3.2): every downloadable artifact in one place, using the shared
 * [DownloadItemCard] so a pack, a model and a profile all look and behave the same.
 *
 * The ML Kit entry is the manually installed copy of the EN↔FA translation model, which is
 * what keeps fully offline translation working without the SDK's own downloader.
 */
@Composable
fun ModelsSection(viewModel: SettingsViewModel) {
    val mlKitState by viewModel.mlKitState.collectAsStateWithLifecycle()
    var showMlKitInfo by remember { mutableStateOf(false) }

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
                "%.1f / %.1f MB".format(
                    state.bytesDownloaded / 1_048_576.0,
                    total / 1_048_576.0,
                )
            },
        )

        Spacer(Modifier.height(Dimens.sm))

        Text(
            text = stringResource(R.string.settings_models_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

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