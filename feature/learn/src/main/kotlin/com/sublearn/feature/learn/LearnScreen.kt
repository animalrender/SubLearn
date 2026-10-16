package com.sublearn.feature.learn

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import org.koin.androidx.compose.koinViewModel

/**
 * The offline Dictionary search tab (D-55, D-58, D-60):
 * Searches EN→FA and FA→EN headwords/expressions in the active lexicon pack and lets the user
 * bookmark any entry directly into My Words.
 */
@Composable
fun DictionaryScreen(
    modifier: Modifier = Modifier,
    viewModel: LearnViewModel = koinViewModel(),
) {
    val query by viewModel.dictQuery.collectAsStateWithLifecycle()
    val direction by viewModel.dictDirection.collectAsStateWithLifecycle()
    val entry by viewModel.dictEntry.collectAsStateWithLifecycle()
    val searchResults by viewModel.dictSearchResults.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = direction == "en-fa",
                onClick = { viewModel.setDictDirection("en-fa") },
                label = { Text(stringResource(R.string.dict_dir_en_fa)) },
            )
            FilterChip(
                selected = direction == "fa-en",
                onClick = { viewModel.setDictDirection("fa-en") },
                label = { Text(stringResource(R.string.dict_dir_fa_en)) },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::searchDictionary,
            placeholder = { Text(stringResource(R.string.dict_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (installedPacks.isEmpty()) {
            Text(
                text = stringResource(R.string.dict_no_pack_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val currentEntry = entry
        if (currentEntry == null) {
            if (query.isBlank()) {
                Text(
                    text = stringResource(R.string.dict_empty_state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val phonetic = currentEntry.pronunciationUs ?: currentEntry.pronunciationUk
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(Dimens.lg), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentEntry.headword,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        phonetic?.takeIf { it.isNotBlank() }?.let { ipa ->
                            Text(
                                text = if (ipa.startsWith("/")) ipa else "/$ipa/",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(Dimens.sm))
                        }
                        IconButton(onClick = { viewModel.bookmarkDictionaryEntry(currentEntry) }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.dict_bookmark_entry))
                        }
                    }

                    currentEntry.pos?.takeIf { it.isNotBlank() }?.let { posTag ->
                        Text(
                            text = posTag,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (currentEntry.meanings.isNotEmpty()) {
                        Text(
                            text = currentEntry.meanings.joinToString("، "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    if (currentEntry.englishGlosses.isNotEmpty()) {
                        Text(
                            text = currentEntry.englishGlosses.take(3).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (currentEntry.synonyms.isNotEmpty()) {
                        Text(
                            text = "Synonyms: ${currentEntry.synonyms.take(10).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (currentEntry.antonyms.isNotEmpty()) {
                        Text(
                            text = "Antonyms: ${currentEntry.antonyms.take(8).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (currentEntry.wordFamily.isNotEmpty()) {
                        Text(
                            text = "Family: ${currentEntry.wordFamily.take(8).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (currentEntry.phrasalVerbs.isNotEmpty() || currentEntry.idioms.isNotEmpty()) {
                        val related = (currentEntry.phrasalVerbs + currentEntry.idioms + currentEntry.collocations).distinct().take(8)
                        Text(
                            text = "Expressions: ${related.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (currentEntry.examples.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.popup_examples_title), style = MaterialTheme.typography.labelMedium)
                        currentEntry.examples.take(4).forEach { (enEx, faEx) ->
                            Text("• $enEx", style = MaterialTheme.typography.bodySmall)
                            if (faEx.isNotBlank()) {
                                Text(
                                    text = "  $faEx",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (searchResults.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    searchResults.take(12).forEach { candidate ->
                        FilterChip(
                            selected = candidate.headword.equals(currentEntry.headword, ignoreCase = true),
                            onClick = { viewModel.selectDictionaryEntry(candidate) },
                            label = { Text(candidate.headword) },
                        )
                    }
                }
            }
        }
    }
}
