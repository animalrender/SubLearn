package com.sublearn.feature.learn

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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.designsystem.R
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import org.koin.androidx.compose.koinViewModel

/**
 * The Learn tab: the three learning modes and the knobs that matter in each, plus a way into My
 * Words (LRN-5).
 *
 * Deliberately no content of its own: everything here writes to the same settings tree the player
 * reads, so there is never a second copy of "what learning mode means".
 */
@Composable
fun LearnScreen(
    onOpenWords: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LearnViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val levelState by viewModel.levelState.collectAsStateWithLifecycle()
    val learning = settings.learning

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_learning_mode), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningMode.entries.forEach { mode ->
                FilterChip(
                    selected = learning.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }
        Text(
            text = stringResource(
                when (learning.mode) {
                    LearningMode.ENTERTAINMENT -> R.string.learn_mode_entertainment_hint
                    LearningMode.LEARNING -> R.string.learn_mode_learning_hint
                    LearningMode.OFF -> R.string.learn_mode_off_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_level), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    CefrLevel.entries.filter { it != CefrLevel.UNKNOWN }.forEach { level ->
                        FilterChip(
                            selected = learning.manualLevel == level,
                            onClick = { viewModel.setLevel(level) },
                            label = { Text(level.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.learn_level_source_hint, levelState.importedWords),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_level_import)) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.settings_popup_count), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = learning.maxPopupCards.toFloat(),
                    onValueChange = { viewModel.setPopupCount(it.toInt()) },
                    valueRange = 1f..10f,
                )
                Text("${learning.maxPopupCards}", style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.settings_popup_lifetime), style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = learning.popupLifetimeMs.toFloat(),
                    onValueChange = { viewModel.setPopupLifetime(it.toLong()) },
                    valueRange = 1_500f..15_000f,
                    steps = 8,
                )
                Text("${learning.popupLifetimeMs / 1000}s", style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.menu_my_words), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenWords) { Text(stringResource(R.string.words_title)) }
        }
    }
}

private fun LearningMode.labelRes(): Int = when (this) {
    LearningMode.ENTERTAINMENT -> R.string.settings_mode_entertainment
    LearningMode.LEARNING -> R.string.settings_mode_learning
    LearningMode.OFF -> R.string.settings_mode_off
}
