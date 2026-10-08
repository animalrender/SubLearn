package com.sublearn.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.designsystem.R
import org.koin.compose.viewmodel.koinViewModel

/**
 * Home: the entry point for local video (GEN / APP entry).
 *
 * Layout is a short list of resumable items under two big actions, because the common case is
 * "continue what I was watching", not browse. The pickers use SAF, so no runtime permission prompt
 * appears for local files.
 */
@Composable
fun HomeScreen(
    onPlay: (uri: String, title: String) -> Unit,
    onOpenUrl: (url: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var showUrlDialog by remember { mutableStateOf(false) }
    val videoPicker = rememberLauncherForActivityResult(OpenVideoContract()) { picked ->
        picked?.let { onPlay(it.uri, MediaInfo.titleFromFileName(it.title)) }
    }

    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { videoPicker.launch(Unit) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.Movie, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.home_open_file))
            }
            FilledTonalButton(
                onClick = { showUrlDialog = true },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.home_open_url))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_recent_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (recent.isNotEmpty()) {
                TextButton(onClick = viewModel::forgetAll) { Text(stringResource(R.string.home_clear_recent)) }
            }
        }

        if (recent.isEmpty()) {
            EmptyState(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(recent, key = { it.uri }) { row ->
                    RecentCard(
                        row = row,
                        onOpen = { onPlay(row.uri, row.title) },
                        onForget = { viewModel.forget(row.uri) },
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        UrlDialog(
            onDismiss = { showUrlDialog = false },
            onOpen = {
                showUrlDialog = false
                onOpenUrl(it)
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.home_recent_empty), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(R.string.home_recent_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentCard(row: RecentVideoRow, onOpen: () -> Unit, onForget: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.isOpenable, role = Role.Button, onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onForget, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.home_remove_recent),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = if (!row.isOpenable) {
                    stringResource(R.string.home_not_playable)
                } else if (row.durationLabel.isNotEmpty()) {
                    row.positionLabel + " / " + row.durationLabel
                } else {
                    row.positionLabel
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.progressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { row.progressFraction },
                        modifier = Modifier.widthIn(min = 60.dp).fillMaxWidth(0.4f).height(4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.url_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.url_dialog_body), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.url_dialog_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onOpen(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_open))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

