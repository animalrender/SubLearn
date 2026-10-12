package com.sublearn.feature.words

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.WordStatus
import com.sublearn.core.data.WordsExportKind
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.Palette
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.badgeColorArgb
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole
import org.koin.androidx.compose.koinViewModel

/**
 * My Words (LRN-4, D-49, D-50): the saved vocabulary and expressions list, filters,
 * JSON/CSV/Anki TSV export & import, and jump back into the video that taught it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyWordsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenContext: (uri: String, startMs: Long) -> Unit = { _, _ -> },
    viewModel: WordsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeFilter by viewModel.filter.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }

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
                actions = {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.words_export))
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.words_import))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.lg),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setQuery(it)
                },
                placeholder = { Text(stringResource(R.string.action_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WordFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == activeFilter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(stringResource(filter.labelRes())) },
                    )
                }
            }
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = stringResource(R.string.words_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.sm))
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.words_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.xl),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = Dimens.xl),
                    verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
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

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.words_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    TextButton(
                        onClick = {
                            copyWordsToClipboard(context, viewModel.exportVocabulary(WordsExportKind.JSON))
                            showExportDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.words_export_json))
                    }
                    TextButton(
                        onClick = {
                            copyWordsToClipboard(context, viewModel.exportVocabulary(WordsExportKind.CSV))
                            showExportDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.words_export_csv))
                    }
                    TextButton(
                        onClick = {
                            copyWordsToClipboard(context, viewModel.exportVocabulary(WordsExportKind.ANKI_TSV))
                            showExportDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.words_export_anki_tsv))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.words_import)) },
            text = {
                OutlinedTextField(
                    value = importInput,
                    onValueChange = { importInput = it },
                    placeholder = { Text(stringResource(R.string.words_import_paste_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importVocabulary(importInput) {
                            importInput = ""
                            showImportDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun copyWordsToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("SubLearn Vocabulary", text))
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
    val titleColor = when (word.phraseType?.uppercase()) {
        "PHRASAL" -> Color(Palette.PROUD_PHRASAL_TEXT)
        "IDIOM" -> Color(Palette.PROUD_IDIOM_TEXT)
        "COLLOCATION" -> Color(Palette.PROUD_COLLOC_TEXT)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(Dimens.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.word,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                word.phraseType?.takeIf { it.isNotBlank() }?.let { pt ->
                    PhraseTypeChip(pt, titleColor)
                    Spacer(Modifier.size(Dimens.xs))
                }
                word.level?.let { LevelChip(it) }
                Spacer(Modifier.size(Dimens.xs))
                StatusChip(status = word.status, onClick = onCycleStatus)
            }
            val lemma = word.lemma
            if (!lemma.isNullOrBlank() && !lemma.equals(word.word, ignoreCase = true)) {
                Text(
                    text = stringResource(R.string.words_lemma_prefix, lemma),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.xxs),
                )
            }
            word.translation?.let {
                Text(
                    text = it,
                    style = noteStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Dimens.xs),
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
                        .padding(top = Dimens.xs)
                        .clickable(enabled = word.sourceStartMs >= 0L, onClick = onOpenContext),
                )
            }
            if (editing) {
                OutlinedTextField(
                    value = word.note ?: "",
                    onValueChange = onNote,
                    placeholder = { Text(stringResource(R.string.words_note_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimens.xs),
                    minLines = 2,
                )
            } else {
                word.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Dimens.xs))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { editing = !editing }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.words_note_hint))
                }
                IconButton(onClick = onOpenContext) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.words_jump_to_video))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove))
                }
                Spacer(Modifier.weight(1f))
                word.sourceTitle?.takeIf { it.isNotBlank() }?.let { src ->
                    Text(
                        text = src,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhraseTypeChip(phraseType: String, tint: Color) {
    val labelRes = when (phraseType.uppercase()) {
        "PHRASAL" -> R.string.popup_phrase_kind_phrasal
        "IDIOM" -> R.string.popup_phrase_kind_idiom
        "COLLOCATION" -> R.string.popup_phrase_kind_collocation
        else -> R.string.words_filter_phrases
    }
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(Dimens.radiusSm))
            .padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
    )
}

@Composable
private fun LevelChip(level: String) {
    val parsed = CefrLevel.entries.firstOrNull { it.name == level } ?: CefrLevel.UNKNOWN
    Text(
        text = level,
        style = MaterialTheme.typography.labelSmall,
        color = Color(parsed.badgeColorArgb()),
        modifier = Modifier
            .padding(end = Dimens.xs)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(Dimens.radiusSm))
            .padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
    )
}

@Composable
private fun StatusChip(status: WordStatus, onClick: () -> Unit) {
    Text(
        text = stringResource(status.labelRes()),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(Dimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
    )
}

private fun WordFilter.labelRes(): Int = when (this) {
    WordFilter.ALL -> R.string.words_filter_all
    WordFilter.WORDS -> R.string.words_filter_words
    WordFilter.PHRASES -> R.string.words_filter_phrases
    WordFilter.PHRASAL -> R.string.words_filter_phrasal
    WordFilter.IDIOM -> R.string.words_filter_idioms
    WordFilter.COLLOCATION -> R.string.words_filter_collocations
    WordFilter.NEW -> R.string.words_status_new
    WordFilter.LEARNING -> R.string.words_status_learning
    WordFilter.KNOWN -> R.string.words_status_known
}

private fun WordStatus.labelRes(): Int = when (this) {
    WordStatus.NEW -> R.string.words_status_new
    WordStatus.LEARNING -> R.string.words_status_learning
    WordStatus.KNOWN -> R.string.words_status_known
}
