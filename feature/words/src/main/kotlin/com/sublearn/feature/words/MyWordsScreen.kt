package com.sublearn.feature.words

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.WordStatus
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole
import org.koin.androidx.compose.koinViewModel

/**
 * My Words (LRN-4): the list, the status a word has, and the jump back into the video that taught it.
 *
 * Persian text is rendered right-to-left per row, because a saved phrase may be in either language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyWordsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenContext: (uri: String, startMs: Long) -> Unit = { _, _ -> },
    viewModel: WordsViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.words_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; viewModel.setQuery(it) },
                placeholder = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WordFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == WordFilter.ALL,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(stringResource(filter.labelRes())) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.words_count, count), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.words_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = { it.id }) { word ->
                        WordCard(
                            word = word,
                            onOpenContext = { onOpenContext(word.sourceUri.orEmpty(), word.sourceStartMs) },
                            onCycleStatus = {
                                val next = WordStatus.entries[(word.status.ordinal + 1) % WordStatus.entries.size]
                                viewModel.setStatus(word, next)
                            },
                            onDelete = { viewModel.remove(word) },
                            onNote = { viewModel.setNote(word, it) },
                            noteStyle = settings.fontFor(FontSurface.WORD_CARD, SubtitleLayerRole.NATIVE).toTextStyle(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordCard(
    word: MarkedWord,
    onOpenContext: () -> Unit,
    onCycleStatus: () -> Unit,
    onDelete: () -> Unit,
    onNote: (String) -> Unit,
    noteStyle: androidx.compose.ui.text.TextStyle,
) {
    var editing by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.word,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                word.level?.let { LevelChip(it) }
                Spacer(Modifier.size(6.dp))
                StatusChip(status = word.status, onClick = onCycleStatus)
            }
            word.translation?.let {
                Text(
                    text = it,
                    style = noteStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            word.contextText?.let { context ->
                Text(
                    text = context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable(enabled = word.sourceStartMs >= 0L, onClick = onOpenContext),
                )
            }
            if (editing) {
                OutlinedTextField(
                    value = word.note ?: "",
                    onValueChange = onNote,
                    placeholder = { Text(stringResource(R.string.words_note_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    minLines = 2,
                )
            } else {
                word.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { editing = !editing }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.words_note_hint))
                }
                IconButton(onClick = onOpenContext) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.words_context_label))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove))
                }
            }
        }
    }
}

@Composable
private fun LevelChip(level: String) {
    val parsed = CefrLevel.entries.firstOrNull { it.name == level } ?: CefrLevel.UNKNOWN
    Text(
        text = level,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusChip(status: WordStatus, onClick: () -> Unit) {
    Text(
        text = stringResource(status.labelRes()),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun WordFilter.labelRes(): Int = when (this) {
    WordFilter.ALL -> R.string.words_filter_all
    WordFilter.NEW -> R.string.words_status_new
    WordFilter.LEARNING -> R.string.words_status_learning
    WordFilter.KNOWN -> R.string.words_status_known
}

private fun WordStatus.labelRes(): Int = when (this) {
    WordStatus.NEW -> R.string.words_status_new
    WordStatus.LEARNING -> R.string.words_status_learning
    WordStatus.KNOWN -> R.string.words_status_known
}

