package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.badgeColorArgb
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.designsystem.toTextStyle

/**
 * The translation cards (LRN-1). Word, line and block share one card so a tap never changes the
 * visual language of the app, only the amount of text on it.
 *
 * A missing ML Kit model is shown as an action, not an error dialog: downloading the model is the
 * only way forward and the button takes the user straight there.
 */
@Composable
internal fun PopupLayer(
    popup: PopupUi?,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onToggleMark: () -> Unit,
    onDownloadModel: () -> Unit,
    onAskAi: (String) -> Unit,
    modifier: Modifier = Modifier,
    registry: PlayerHitRegistry? = null,
) {
    val reduce = LocalReduceMotion.current
    // The card keeps its last content while it animates out; without this it went blank mid-exit.
    val lastShown = remember { mutableStateOf(popup) }
    LaunchedEffect(popup) { if (popup != null) lastShown.value = popup }
    AnimatedVisibility(
        visible = popup != null,
        enter = Motion.popupEnter(reduce),
        exit = Motion.popupExit(reduce),
        modifier = modifier.fillMaxSize(),
    ) {
        val current = popup ?: lastShown.value ?: return@AnimatedVisibility
        val spec = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.NATIVE)
        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .blocksGestures(registry)
                    .widthIn(max = Dimens.cardMaxWidth)
                    .padding(Dimens.lg),
                shape = RoundedCornerShape(settings.appearance.cornerRadiusDp.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = Dimens.cardElevation,
                shadowElevation = spec.shadowElevationDp.dp,
            ) {
                Column(
                    modifier = Modifier
                        .padding(Dimens.lg)
                        .heightIn(max = Dimens.cardMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                when (current.kind) {
                                    PopupKind.WORD -> R.string.translate_word
                                    PopupKind.LINE -> R.string.translate_line
                                    PopupKind.BLOCK -> R.string.translate_block
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        current.level?.let { level ->
                            Spacer(Modifier.size(Dimens.sm))
                            LevelBadge(level)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }

                    Text(
                        text = current.sourceText,
                        style = settings.fontFor(FontSurface.TRANSLATION_POPUP, SubtitleLayerRole.LEARNING)
                            .toTextStyle(LocalAppFontScale.current),
                        maxLines = 6,
                    )

                    if (current.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                            CircularProgressIndicator(Modifier.size(Dimens.spinnerSmall), strokeWidth = Dimens.spinnerStroke)
                            Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        current.translation?.let { translated ->
                            Text(
                                text = translated,
                                style = spec.toTextStyle(LocalAppFontScale.current),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        current.contextTranslation?.let { context ->
                            Text(
                                text = context,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        current.error?.let { error ->
                            Column {
                                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                if (current.needsModelDownload) {
                                    TextButton(onClick = onDownloadModel) { Text(stringResource(R.string.translate_download)) }
                                }
                            }
                        }
                        if (current.translation == null && current.error == null && !current.busy) {
                            Text(
                                stringResource(R.string.translate_offline_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleMark) {
                            Icon(
                                imageVector = if (current.marked) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = stringResource(
                                    if (current.marked) R.string.word_remove else R.string.word_add,
                                ),
                                tint = if (current.marked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { onAskAi(current.sourceText) }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.ai_explain))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = clock(current.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LevelBadge(level: String, modifier: Modifier = Modifier) {
    val parsed = CefrLevel.entries.firstOrNull { it.name == level } ?: CefrLevel.UNKNOWN
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimens.radiusSm),
        color = Color(parsed.badgeColorArgb()).copy(alpha = 0.2f),
    ) {
        Text(
            text = level,
            style = MaterialTheme.typography.labelSmall,
            color = Color(parsed.badgeColorArgb()),
            modifier = Modifier.padding(horizontal = Dimens.sm, vertical = Dimens.xxs),
        )
    }
}

/** The answer sheet for the AI helper (AI-2, AI-3): the sections the prompt asked for, in order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnswerSheet(
    ui: PlayerUi,
    onDismiss: () -> Unit,
    onAskAgain: () -> Unit,
    onResume: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ai = ui.ai
    val answerText = ui.ai.answer?.text ?: ""
    val sections = remember(answerText) { com.sublearn.core.ai.AiAnswerParser.parse(answerText) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.lg, vertical = Dimens.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_answer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (ai.pausedForAnswer) {
                    TextButton(onClick = onResume) { Text(stringResource(R.string.ai_resume)) }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            ai.question?.let { question ->
                Text(question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (ai.state) {
                AiState.BUSY -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                ) {
                    CircularProgressIndicator(Modifier.size(Dimens.inlineIcon), strokeWidth = Dimens.spinnerStroke)
                    Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodySmall)
                    if (ui.playback.isPlaying.not()) {
                        Text(stringResource(R.string.ai_pause_hint), style = MaterialTheme.typography.labelSmall)
                    }
                }

                AiState.ERROR -> Column {
                    Text(
                        stringResource(R.string.ai_error, ai.error ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        TextButton(onClick = onAskAgain) { Text(stringResource(R.string.ai_ask_again)) }
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings_category_ai)) }
                    }
                }

                AiState.ANSWER -> {
                    Section(R.string.ai_section_meaning, sections.meaning)
                    Section(R.string.ai_section_why, sections.whyUsed)
                    Section(R.string.ai_section_synonyms, sections.synonyms)
                    Section(R.string.ai_section_elsewhere, sections.elsewhere)
                    Section(R.string.word_details, sections.extra)
                    ai.answer?.let { answer ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${answer.providerId} · ${answer.model}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onCopy(answer.text) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.ai_copy))
                            }
                            IconButton(onClick = onAskAgain) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ai_ask_again))
                            }
                        }
                    }
                }

                AiState.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun Section(titleRes: Int, body: String?) {
    if (body.isNullOrBlank()) return
    Column {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Dimens.xs))
    }
}
