package com.sublearn.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.common.FeatureFlag
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.FontFamilyToken
import com.sublearn.core.settings.FontSpec
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.FontWeightToken
import com.sublearn.core.settings.ImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sublearn.core.settings.QuickActionColumn
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.TextDecorationToken
import com.sublearn.core.settings.TranslationProviderToken
import com.sublearn.core.ai.AiPromptBuilder
import kotlinx.coroutines.launch

private fun ImportMode.labelRes(): Int = when (this) {
    ImportMode.MERGE -> R.string.settings_import_mode_merge
    ImportMode.REPLACE -> R.string.settings_import_mode_replace
}

/** Reads a picked JSON or text file without any storage permission. */
private suspend fun android.content.Context.readTextFile(uri: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val input = android.net.Uri.parse(uri).let { contentResolver.openInputStream(it) }
        input?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}

/** GEN-3: one editor for every text surface, driven by [FontSurface]. */
@Composable
fun FontsSection(settings: AppSettings, viewModel: SettingsViewModel) {
    var surface by remember { mutableStateOf(FontSurface.SUBTITLE_LEARNING) }
    var role by remember { mutableStateOf(SubtitleLayerRole.LEARNING) }
    val surfaces = FontSurface.entries
    SectionCard(stringResource(R.string.settings_category_fonts)) {
        Text(
            stringResource(R.string.settings_font_surface_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_font_surface),
            options = surfaces,
            selected = surface,
            label = { it.label() },
            onSelect = { surface = it },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_font_role),
            options = SubtitleLayerRole.entries,
            selected = role,
            label = { it.name.lowercase().replace('_', ' ') },
            onSelect = { role = it },
        )
        val spec = settings.fontRegistry.styleFor(surface, role)
        val overriden = settings.fontRegistry.hasOverride(surface, role)
        FontEditor(spec, overriden) { next ->
            viewModel.update { it.copy(fonts = it.fontRegistry.withOverride(surface, role, next)) }
        }
        ActionRow(
            title = stringResource(R.string.settings_font_reset_surface),
            onClick = { viewModel.update { it.copy(fonts = it.fontRegistry.withoutOverride(surface, role)) } },
        )
    }
}

@Composable
private fun FontEditor(spec: FontSpec, overriden: Boolean, onChange: (FontSpec) -> Unit) {
    val previewStyle: TextStyle = spec.toTextStyle()
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
        PreviewText(previewStyle = spec.toTextStyle())
        ChoiceRow(
            title = stringResource(R.string.settings_font_family),
            options = FontFamilyToken.entries,
            selected = spec.family,
            label = { it.name.lowercase().replace('_', ' ') },
            onSelect = { value -> onChange(spec.copy(family = value)) },
        )
        SliderRow(
            title = stringResource(R.string.settings_font_size),
            value = spec.sizeSp,
            range = 12f..48f,
            valueLabel = "%.1f sp".format(spec.sizeSp),
            onValueChange = { value -> onChange(spec.copy(sizeSp = value)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_font_weight),
            options = FontWeightToken.entries,
            selected = spec.weight,
            label = { it.name.lowercase().replace('_', ' ') },
            onSelect = { value -> onChange(spec.copy(weight = value)) },
        )
        SliderRow(
            title = stringResource(R.string.settings_font_line_height),
            value = spec.lineHeightEm,
            range = 0.9f..2.2f,
            valueLabel = "%.2f".format(spec.lineHeightEm),
            onValueChange = { value -> onChange(spec.copy(lineHeightEm = value)) },
        )
        SliderRow(
            title = stringResource(R.string.settings_font_max_lines),
            value = spec.maxLines.toFloat(),
            range = 1f..8f,
            valueLabel = spec.maxLines.toString(),
            onValueChange = { value -> onChange(spec.copy(maxLines = value.toInt())) },
        )
        SliderRow(
            title = stringResource(R.string.settings_font_outline),
            value = spec.outlineAlpha,
            range = 0f..1f,
            valueLabel = "%.2f".format(spec.outlineAlpha),
            onValueChange = { value -> onChange(spec.copy(outlineAlpha = value)) },
        )
        SliderRow(
            title = stringResource(R.string.settings_font_background),
            value = spec.backgroundAlpha,
            range = 0f..1f,
            valueLabel = "%.2f".format(spec.backgroundAlpha),
            onValueChange = { value -> onChange(spec.copy(backgroundAlpha = value)) },
        )
        SliderRow(
            title = stringResource(R.string.settings_transparency_hint),
            value = spec.letterSpacingEm,
            range = -0.05f..0.3f,
            valueLabel = "%.2f em".format(spec.letterSpacingEm),
            onValueChange = { value -> onChange(spec.copy(letterSpacingEm = value)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_font_decoration),
            options = TextDecorationToken.entries,
            selected = spec.decoration,
            label = { it.name.lowercase().replace('_', ' ') },
            onSelect = { value -> onChange(spec.copy(decoration = value)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_font_italic),
            value = spec.italic,
            onChange = { value -> onChange(spec.copy(italic = value)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_font_caps),
            value = spec.allCaps,
            onChange = { value -> onChange(spec.copy(allCaps = value)) },
        )
        if (overriden) {
            Text(
                stringResource(R.string.settings_font_custom_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun PreviewText(previewStyle: TextStyle) {
    Text(
        text = stringResource(R.string.settings_font_preview),
        style = previewStyle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.sm),
    )
}

@Composable
private fun FontSurface.label(): String = when (this) {
    FontSurface.SUBTITLE_LEARNING -> stringResource(R.string.subtitle_layer_learning)
    FontSurface.SUBTITLE_TRANSLATION -> stringResource(R.string.subtitle_layer_translation)
    FontSurface.SUBTITLE_LIST -> stringResource(R.string.subtitle_list)
    FontSurface.WORD_CARD -> stringResource(R.string.word_details)
    FontSurface.AI_ANSWER -> stringResource(R.string.ai_answer)
    FontSurface.APP_MENUS,
    FontSurface.TRANSLATION_POPUP,
    FontSurface.PLAYER_CHROME,
    FontSurface.LEARNING_POPUP,
    -> name.lowercase().replace('_', ' ')
}

/** AI provider, key and prompt (AI-4). */
@Composable
fun AiSection(settings: AppSettings, viewModel: SettingsViewModel, onOpenPrompt: () -> Unit) {
    val ai = settings.ai
    var keyDraft by remember { mutableStateOf("") }
    SectionCard(stringResource(R.string.settings_category_ai)) {
        SwitchRow(
            title = stringResource(R.string.settings_ai_enabled),
            value = ai.enabled,
            onChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(enabled = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_ai_provider),
            options = AiProviderToken.entries,
            selected = ai.provider,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(ai = it.ai.copy(provider = value)) } },
        )
        TextRow(
            title = stringResource(R.string.settings_ai_model),
            value = ai.model,
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(model = value)) } },
        )
        TextRow(
            title = stringResource(R.string.settings_ai_base_url),
            value = ai.customBaseUrl,
            placeholder = "https://api.example.com",
            helper = stringResource(R.string.settings_ai_url_warning),
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(customBaseUrl = value)) } },
        )
        TextRow(
            title = stringResource(R.string.settings_ai_key),
            value = keyDraft,
            secret = true,
            singleLine = true,
            placeholder = if (viewModel.hasKey()) stringResource(R.string.settings_ai_key_saved) else "sk-…",
            onValueChange = { keyDraft = it },
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.sm)) {
            TextButton(onClick = { viewModel.saveApiKey(keyDraft); keyDraft = "" }) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(onClick = { viewModel.saveApiKey(""); keyDraft = "" }) {
                Text(stringResource(R.string.settings_ai_key_clear))
            }
            TextButton(onClick = viewModel::testConnection) {
                Text(stringResource(R.string.settings_ai_test))
            }
        }
        SliderRow(
            title = stringResource(R.string.settings_ai_context),
            value = ai.contextBlocks.toFloat(),
            range = 0f..40f,
            valueLabel = ai.contextBlocks.toString(),
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(contextBlocks = value.toInt())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_ai_temperature),
            value = ai.temperaturePercent.toFloat(),
            range = 0f..100f,
            valueLabel = "${ai.temperaturePercent}%",
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(temperaturePercent = value.toInt())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_ai_max_tokens),
            value = ai.maxOutputTokens.toFloat(),
            range = 100f..4000f,
            steps = 38,
            valueLabel = ai.maxOutputTokens.toString(),
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(maxOutputTokens = value.toInt())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_ai_timeout),
            value = ai.timeoutMs.toFloat() / 1000f,
            range = 5f..120f,
            valueLabel = "%.0f s".format(ai.timeoutMs / 1000f),
            onValueChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(timeoutMs = (value * 1000).toLong())) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_ai_include_title),
            value = ai.includeTitle,
            onChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(includeTitle = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_ai_include_time),
            value = ai.includeTimestamps,
            onChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(includeTimestamps = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_ai_pause),
            value = ai.pausePlayback,
            onChange = { value -> viewModel.update { it.copy(ai = it.ai.copy(pausePlayback = value)) } },
        )
        ActionRow(
            title = stringResource(R.string.settings_prompt_title),
            subtitle = stringResource(R.string.settings_prompt_variables),
            onClick = onOpenPrompt,
            showArrow = true,
        )
    }
}

/** The prompt editor, with the variables it actually understands. */
@Composable
fun PromptSection(settings: AppSettings, viewModel: SettingsViewModel, onDone: () -> Unit) {
    var draft by remember { mutableStateOf(settings.ai.promptTemplate) }
    val unknown = remember(draft) { AiPromptBuilder.unknownVariables(draft) }
    SectionCard(stringResource(R.string.settings_prompt_title)) {
        androidx.compose.foundation.text.BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.sm),
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 12,
        )
        Text(
            stringResource(R.string.settings_ai_prompt_hint, AiPromptBuilder.KNOWN_VARIABLES.joinToString { "\${$it}" }),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unknown.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_ai_prompt_unknown, unknown.joinToString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.sm)) {
            TextButton(onClick = { viewModel.update { it.copy(ai = it.ai.copy(promptTemplate = draft)) } }) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(
                onClick = {
                    draft = com.sublearn.core.settings.AiSettings.DEFAULT_PROMPT
                    viewModel.update { it.copy(ai = it.ai.copy(promptTemplate = com.sublearn.core.settings.AiSettings.DEFAULT_PROMPT)) }
                },
            ) { Text(stringResource(R.string.settings_ai_prompt_reset)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
fun TranslationSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val translation = settings.translation
    val modelProgress by viewModel.modelProgress.collectAsStateWithLifecycle()
    SectionCard(stringResource(R.string.settings_category_translation)) {
        ChoiceRow(
            title = stringResource(R.string.settings_translation_provider),
            options = TranslationProviderToken.entries,
            selected = translation.provider,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(translation = it.translation.copy(provider = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_auto_download_models),
            value = translation.autoDownloadModels,
            onChange = { value -> viewModel.update { it.copy(translation = it.translation.copy(autoDownloadModels = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_translation_cache),
            value = translation.cacheEnabled,
            onChange = { value -> viewModel.update { it.copy(translation = it.translation.copy(cacheEnabled = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_cache_max),
            value = translation.cacheMaxEntries.toFloat(),
            range = 200f..20000f,
            valueLabel = translation.cacheMaxEntries.toString(),
            onValueChange = { value -> viewModel.update { it.copy(translation = it.translation.copy(cacheMaxEntries = value.toInt())) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_whole_block),
            value = translation.translateWholeBlockFirst,
            onChange = { value -> viewModel.update { it.copy(translation = it.translation.copy(translateWholeBlockFirst = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_fallback_online),
            value = translation.fallbackToOnline,
            subtitle = stringResource(R.string.settings_fallback_online_hint),
            onChange = { value -> viewModel.update { it.copy(translation = it.translation.copy(fallbackToOnline = value)) } },
        )
        if (modelProgress != null) {
            LinearProgressIndicator(
                progress = { modelProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.translate_downloading), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.sm)) {
            TextButton(onClick = viewModel::downloadModel) { Text(stringResource(R.string.translate_download)) }
            TextButton(onClick = viewModel::clearTranslationCache) { Text(stringResource(R.string.settings_translation_clear_cache)) }
        }
    }
}

@Composable
fun DictionarySection(settings: AppSettings, viewModel: SettingsViewModel) {
    val dictionary = settings.dictionary
    val enabled = FeatureFlag.isEnabled(FeatureFlag.OFFLINE_DICTIONARY)
    SectionCard(stringResource(R.string.settings_category_dictionary)) {
        Text(
            stringResource(R.string.settings_dictionary_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!enabled) {
            LaterRow(
                title = stringResource(R.string.settings_dictionary_import),
                detail = stringResource(R.string.coming_soon_body),
            )
        } else {
            val picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("application/octet-stream"))) { picked ->
                if (picked != null) {
                    viewModel.update { it.copy(dictionary = it.dictionary.copy(databaseFileKey = picked.uri, importEnabled = true)) }
                }
            }
            SwitchRow(
                title = stringResource(R.string.settings_dictionary_import),
                value = dictionary.importEnabled,
                onChange = { value -> viewModel.update { it.copy(dictionary = it.dictionary.copy(importEnabled = value)) } },
            )
            ActionRow(
                title = stringResource(R.string.settings_dictionary_import),
                subtitle = stringResource(
                    R.string.settings_dictionary_import_state,
                    dictionary.databaseFileKey ?: "—",
                ),
                onClick = { picker.launch(arrayOf("application/octet-stream")) },
            )
        }
        SliderRow(
            title = stringResource(R.string.settings_dictionary_timeout),
            value = dictionary.lookupTimeoutMs.toFloat(),
            range = 200f..5000f,
            valueLabel = "${dictionary.lookupTimeoutMs} ms",
            onValueChange = { value -> viewModel.update { it.copy(dictionary = it.dictionary.copy(lookupTimeoutMs = value.toLong())) } },
        )
    }
}

/** SUB-7: which buttons exist, where they live, how big and how see-through. */
@Composable
fun QuickActionsSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val quick = settings.quickActions
    SectionCard(stringResource(R.string.settings_category_quick_actions)) {
        Text(
            stringResource(R.string.settings_quick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_quick_column),
            options = QuickActionColumn.entries,
            selected = quick.column,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(quickActions = it.quickActions.copy(column = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_icons_per_row),
            value = quick.barIconsPerRow.toFloat(),
            range = 4f..12f,
            valueLabel = quick.barIconsPerRow.toString(),
            onValueChange = { value -> viewModel.update { it.copy(quickActions = it.quickActions.copy(barIconsPerRow = value.toInt())) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_auto_hide_with_controls),
            value = quick.autoHideWithControls,
            onChange = { value -> viewModel.update { it.copy(quickActions = it.quickActions.copy(autoHideWithControls = value)) } },
        )
    }
    SectionCard(stringResource(R.string.settings_dock_mode)) {
        QuickActionId.entries.forEach { id ->
            val spec = quick.spec(id)
            Column(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
                Text(id.key.replace('_', ' '), style = MaterialTheme.typography.bodyLarge)
                ChoiceRow(
                    title = stringResource(R.string.settings_dock_mode),
                    options = DockMode.entries,
                    selected = spec.dock,
                    label = { stringResource(it.labelRes()) },
                    onSelect = { value ->
                        viewModel.update { it.copy(quickActions = it.quickActions.updated(id, spec.copy(dock = value))) }
                    },
                )
                SliderRow(
                    title = stringResource(R.string.subtitle_size),
                    value = spec.sizeDp,
                    range = 24f..72f,
                    valueLabel = "${spec.sizeDp.toInt()} dp",
                    onValueChange = { value ->
                        viewModel.update { it.copy(quickActions = it.quickActions.updated(id, spec.copy(sizeDp = value))) }
                    },
                )
                SliderRow(
                    title = stringResource(R.string.subtitle_transparency),
                    value = spec.transparencyPercent.toFloat(),
                    range = 0f..90f,
                    valueLabel = "${spec.transparencyPercent}%",
                    onValueChange = { value ->
                        viewModel.update { it.copy(quickActions = it.quickActions.updated(id,
                            spec.copy(transparencyPercent = value.toInt()))) }
                    },
                )
                StepperRow(
                    title = stringResource(R.string.settings_quick_order),
                    value = spec.order,
                    range = 0..30,
                    step = 1,
                    onChange = { value ->
                        viewModel.update { it.copy(quickActions = it.quickActions.updated(id, spec.copy(order = value))) }
                    },
                )
            }
        }
    }
}

/** SET-6 and the honest "about" screen: version, licence, privacy, export, import, reset. */
@Composable
fun AboutSection(settings: AppSettings, viewModel: SettingsViewModel, versionName: String, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf(ImportMode.MERGE) }
    SectionCard(stringResource(R.string.settings_category_about)) {
        Text(
            stringResource(R.string.settings_about_version, versionName.ifBlank { "0.1.0" }, 1),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.settings_schema_version, AppSettings.SCHEMA_VERSION), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_about_license), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.settings_about_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(
            title = stringResource(R.string.settings_about_source),
            subtitle = stringResource(R.string.settings_about_source_hint),
            onClick = { viewModel.openUrl("https://github.com/animalrender/SubLearn") },
        )
        TextRow(
            title = stringResource(R.string.settings_feedback),
            value = "",
            placeholder = "issues@sublearn.local",
            onValueChange = { },
        )
    }
    SectionCard(stringResource(R.string.settings_export)) {
        Text(
            stringResource(R.string.settings_export_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(title = stringResource(R.string.settings_export), onClick = {
            scope.launch {
                val json = viewModel.exportText()
                clipboard.setText(AnnotatedString(json))
                context.cacheDir.resolve("exports").mkdirs()
                context.cacheDir.resolve("exports/settings.json").writeText(json)
                viewModel.notify(context.getString(R.string.settings_exported))
            }
        })
        val picker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("application/json", "text/plain", "*/*"))) { picked ->
            if (picked != null) {
                scope.launch {
                    val text = context.readTextFile(picked.uri)
                    if (text == null) {
                        viewModel.notify(context.getString(R.string.settings_import_failed, ""))
                    } else {
                        viewModel.importJson(text, importMode)
                    }
                }
            }
        }
        ChoiceRow(
            title = stringResource(R.string.settings_import),
            options = ImportMode.entries,
            selected = importMode,
            label = { mode -> stringResource(mode.labelRes()) },
            onSelect = { importMode = it },
        )
        ActionRow(title = stringResource(R.string.settings_import), onClick = { picker.launch(arrayOf("application/json")) })
    }
    SectionCard(stringResource(R.string.settings_reset_all)) {
        Text(stringResource(R.string.settings_reset_confirm), style = MaterialTheme.typography.bodySmall)
        ActionRow(title = stringResource(R.string.settings_reset_all), onClick = { showReset = true })
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.settings_reset_all)) },
            text = { Text(stringResource(R.string.settings_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetAll(); showReset = false }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
