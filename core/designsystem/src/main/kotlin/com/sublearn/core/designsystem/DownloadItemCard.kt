package com.sublearn.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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

/**
 * The one card for everything Settings → Downloads can install: the translation model, a lexicon
 * pack, an accuracy model.
 *
 * The layout is three stacked rows, never one crowded row: **name and description** (with the
 * details button), **state** (size and download, progress and stop, installed and its detail, an
 * error and retry), and an optional **actions** row that wraps. The earlier single-row design put
 * a Persian name, a size, a verify icon and a delete icon side by side and overflowed on a phone in
 * Persian; stacking is what keeps the words where they belong in both directions (D-87).
 *
 * @param name display name of the item
 * @param description what the item does, up to two lines
 * @param sizeBytes download size in bytes, or null when unknown
 * @param state current download/install state
 * @param onDownload tapped "Download", or "Resume" from [DownloadItemState.PAUSED]
 * @param onCancel tapped the stop button while downloading; null hides the button for a download
 *   that cannot be stopped (ML Kit's), instead of showing a control that would do nothing
 * @param onDelete tapped delete while installed
 * @param onRetry tapped retry after an error
 * @param onInfo tapped the details button
 * @param progress `0f..1f` while downloading or paused; null draws an indeterminate bar for a
 *   download whose size is not known yet
 * @param progressText text beside the bar, e.g. `12.0 MB of 30.4 MB`
 * @param detail a line under the state row, e.g. the size an installed model occupies
 * @param actions extra buttons shown in a wrapping row under the state while installed
 *   (test, verify, activate). Delete is appended automatically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadItemCard(
    name: String,
    description: String,
    sizeBytes: Long?,
    state: DownloadItemState,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit = {},
    onCancel: (() -> Unit)? = null,
    onDelete: () -> Unit = {},
    onRetry: () -> Unit = {},
    onInfo: () -> Unit = {},
    progress: Float? = 0f,
    progressText: String? = null,
    detail: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        tonalElevation = Dimens.tonalElevation,
    ) {
        Column(modifier = Modifier.padding(Dimens.md)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two lines, not one: the accuracy-model cards name the profile the model
                        // unlocks, and truncating that sentence removes the only thing that makes
                        // one encoder distinguishable from another.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // No size override: Material's own touch target is the 48 dp minimum, and a
                // 32 dp box around an 18 dp glyph is a miss on a phone held one-handed.
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.action_info),
                        modifier = Modifier.size(Dimens.inlineIcon),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.sm))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (state) {
                    is DownloadItemState.UNAVAILABLE -> {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimens.inlineIcon),
                        )
                        Spacer(modifier = Modifier.width(Dimens.xs))
                        Text(
                            text = state.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DownloadItemState.AVAILABLE -> {
                        Text(
                            text = sizeBytes?.let(::formatBytes) ?: stringResource(R.string.downloads_size_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
                            Spacer(modifier = Modifier.width(Dimens.xs))
                            Text(stringResource(R.string.action_download))
                        }
                    }

                    DownloadItemState.DOWNLOADING -> {
                        Column(modifier = Modifier.weight(1f)) {
                            if (progress == null) {
                                SubLearnLinearProgressIndeterminate(
                                    modifier = Modifier.fillMaxWidth().height(Dimens.hudTrackWidth),
                                )
                            } else {
                                SubLearnLinearProgress(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(Dimens.hudTrackWidth),
                                )
                            }
                            Text(
                                text = progressText ?: stringResource(R.string.status_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // A stop button, not an "x": the user asked to be able to stop a download,
                        // and a cross next to a progress bar reads as "dismiss this row".
                        if (onCancel != null) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.padding(start = Dimens.sm)) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
                                Spacer(modifier = Modifier.width(Dimens.xs))
                                Text(stringResource(R.string.action_stop))
                            }
                        }
                    }

                    DownloadItemState.PAUSED -> {
                        Column(modifier = Modifier.weight(1f)) {
                            SubLearnLinearProgress(
                                progress = { progress ?: 0f },
                                modifier = Modifier.fillMaxWidth().height(Dimens.hudTrackWidth),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.PauseCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Dimens.spinnerSmall),
                                )
                                Spacer(modifier = Modifier.width(Dimens.xs))
                                Text(
                                    text = progressText ?: stringResource(R.string.status_paused),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = onDownload, modifier = Modifier.padding(start = Dimens.sm)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
                            Spacer(modifier = Modifier.width(Dimens.xs))
                            Text(stringResource(R.string.action_resume))
                        }
                    }

                    DownloadItemState.VERIFYING, DownloadItemState.INSTALLING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.inlineIcon + Dimens.xxs),
                            strokeWidth = Dimens.spinnerStroke,
                        )
                        Spacer(modifier = Modifier.width(Dimens.sm))
                        Text(
                            text = if (state == DownloadItemState.VERIFYING) {
                                stringResource(R.string.status_verifying)
                            } else {
                                stringResource(R.string.status_installing)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DownloadItemState.INSTALLED -> {
                        val installedColor = MaterialTheme.colorScheme.tertiary
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = installedColor,
                            modifier = Modifier.size(Dimens.inlineIcon),
                        )
                        Spacer(modifier = Modifier.width(Dimens.xs))
                        Text(
                            text = stringResource(R.string.status_installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = installedColor,
                        )
                        if (detail != null) {
                            Spacer(modifier = Modifier.width(Dimens.sm))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    is DownloadItemState.ERROR -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Dimens.inlineIcon),
                        )
                        Spacer(modifier = Modifier.width(Dimens.xs))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.retryable) {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }

            if (state == DownloadItemState.INSTALLED) {
                Spacer(modifier = Modifier.height(Dimens.xs))
                // Wrapping, so "Test", "Verify", "Set active" and "Delete" in Persian never push each
                // other off the card.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                    verticalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions?.invoke()
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(Dimens.spinnerSmall))
                        Spacer(modifier = Modifier.width(Dimens.xs))
                        Text(stringResource(R.string.action_delete))
                    }
                }
            } else if (detail != null && state !is DownloadItemState.ERROR) {
                Spacer(modifier = Modifier.height(Dimens.xs))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

sealed class DownloadItemState {
    object AVAILABLE : DownloadItemState()
    object DOWNLOADING : DownloadItemState()

    /** Stopped by the user with a resumable partial file on disk; "Download" continues it. */
    object PAUSED : DownloadItemState()
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

/** `12.3 MB`: one place decides how a size reads, so every card and dialog agrees. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
