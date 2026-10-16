package com.sublearn.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Unified download item card for ML Kit models, packs, and NLP models (§3.2).
 *
 * Shows different states: available, downloading, verifying, installed, error.
 * All download types in Settings → Models & Downloads use this component.
 *
 * @param name display name of the item
 * @param description one-line description of what it does
 * @param sizeBytes download size in bytes (null if unknown)
 * @param state current download/install state
 * @param onDownload called when user taps "Download"
 * @param onCancel called when user taps cancel during download
 * @param onDelete called when user taps delete (installed state)
 * @param onRetry called when user taps retry (error state)
 * @param onInfo called when user taps info icon
 * @param progress download progress 0.0..1.0 (only used in Downloading state)
 * @param progressText optional text like "45% • 12 MB/s"
 * @param detail optional line under the state row, e.g. the size an installed model occupies
 */
@Composable
fun DownloadItemCard(
    name: String,
    description: String,
    sizeBytes: Long?,
    state: DownloadItemState,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDelete: () -> Unit = {},
    onRetry: () -> Unit = {},
    onInfo: () -> Unit = {},
    progress: Float = 0f,
    progressText: String? = null,
    detail: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two lines, not one: the accuracy-model cards name the profile the model
                        // unlocks, and truncating that sentence removes the only thing that makes
                        // one encoder distinguishable from another.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // No size override: Material's own touch target is the 48 dp minimum, and a
                // 32 dp box around an 18 dp glyph is a miss on a phone held one-handed.
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.action_info),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (state) {
                    is DownloadItemState.UNAVAILABLE -> {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DownloadItemState.AVAILABLE -> {
                        sizeBytes?.let {
                            Text(
                                text = formatBytes(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_download))
                        }
                    }

                    DownloadItemState.DOWNLOADING -> {
                        Column(modifier = Modifier.weight(1f)) {
                            SubLearnLinearProgress(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            if (progressText != null) {
                                Text(
                                    text = progressText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }

                    DownloadItemState.VERIFYING, DownloadItemState.INSTALLING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state == DownloadItemState.VERIFYING) {
                                stringResource(R.string.status_verifying)
                            } else {
                                stringResource(R.string.status_installing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // The row lays its children out with SpaceBetween, so without this the
                        // spinner and its label would sit at opposite ends of the card.
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    DownloadItemState.INSTALLED -> {
                        val installedColor = MaterialTheme.colorScheme.tertiary
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = installedColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.status_installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = installedColor,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_delete))
                        }
                    }

                    is DownloadItemState.ERROR -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
}

sealed class DownloadItemState {
    object AVAILABLE : DownloadItemState()
    object DOWNLOADING : DownloadItemState()
    object VERIFYING : DownloadItemState()
    object INSTALLING : DownloadItemState()
    object INSTALLED : DownloadItemState()
    data class ERROR(val message: String, val retryable: Boolean = true) : DownloadItemState()

    /**
     * The item cannot run in this build, so it is listed but not offered.
     *
     * [reason] is shown in place of a download button. An engine that is missing from a build is a
     * fact about the build, not an error the user caused, so it is stated in the neutral body
     * colour rather than the error colour: nothing has gone wrong and there is nothing to retry.
     */
    data class UNAVAILABLE(val reason: String) : DownloadItemState()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}