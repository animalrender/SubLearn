package com.sublearn.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.player.OpenTreeContract
import com.sublearn.core.settings.AccentToken
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.DetailsOpenTrigger
import com.sublearn.core.settings.DictionaryPreference
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.DoubleTapAction
import com.sublearn.core.settings.FontFamilyToken
import com.sublearn.core.settings.FontSpec
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.GestureSlot
import com.sublearn.core.settings.ImportMode
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.LevelProviderToken
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.settings.QuickActionColumn
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.TextDecorationToken
import com.sublearn.core.settings.ThemeMode
import com.sublearn.core.settings.TranslationProviderToken
import com.sublearn.core.settings.FontWeightToken
import com.sublearn.core.subtitles.TrackRole
import org.koin.androidx.compose.koinViewModel

/**
 * The settings tree (SET-1..SET-7).
 *
 * Navigation is a section string rather than a nav graph: settings are a flat list of categories
 * with one level of sub-screens (fonts, prompt), and a search box that jumps to a category is enough
 * to find anything (SET-2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startSection: String = "root",
    versionName: String = "",
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(startSection) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section.settingsTitleRes())) },
                navigationIcon = {
                    val atRoot = section == "root"
                    IconButton(onClick = { if (atRoot) onBack() else section = "root" }) {
                        Icon(
                            imageVector = if (atRoot) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            StatusBanner(status = status, onDismiss = { })

            when (section) {
                "root" -> RootList(query = query, onQuery = { query = it }, onOpen = { section = it })
                "appearance" -> AppearanceSection(settings, viewModel)
                "player" -> PlayerSection(settings, viewModel)
                "subtitles" -> SubtitlesSection(settings, viewModel)
                "fonts" -> FontsSection(settings, viewModel)
                "gestures" -> GesturesSection(settings, viewModel)
                "shadowing" -> ShadowingSection(settings, viewModel)
                "learning" -> LearningSection(settings, viewModel)
                "ai" -> AiSection(settings, viewModel, onOpenPrompt = { section = "prompt" })
                "translation" -> TranslationSection(settings, viewModel)
                "dictionary" -> DictionarySection(settings, viewModel)
                "quick" -> QuickActionsSection(settings, viewModel)
                "prompt" -> PromptSection(settings, viewModel, onDone = { section = "ai" })
                "about" -> AboutSection(settings, viewModel, versionName, onBack)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RootList(query: String, onQuery: (String) -> Unit, onOpen: (String) -> Unit) {
    var search by remember { mutableStateOf(query) }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it; onQuery(it) },
        placeholder = { Text(stringResource(R.string.settings_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
    )
    Sections.entries.forEach { entry ->
        if (search.isBlank() || entry.matches(search)) {
            ActionRow(
                title = stringResource(entry.titleRes),
                subtitle = stringResource(entry.hintRes),
                onClick = { onOpen(entry.id) },
                showArrow = true,
            )
        }
    }
}

private enum class Sections(val id: String, val titleRes: Int, val hintRes: Int, val keywords: List<String>) {
    Appearance("appearance", R.string.settings_category_appearance, R.string.settings_hint_appearance, listOf("theme", "dark", "amoled",
        "accent", "language", "رنگ", "پوسته")),
    Player("player", R.string.settings_category_player, R.string.settings_hint_player, listOf("seek", "speed", "decoder", "aspect", "pip",
        "orientation")),
    Subtitles("subtitles", R.string.settings_category_subtitles, R.string.settings_hint_subtitles, listOf("subtitle", "layer", "delay",
        "srt", "ass")),
    Fonts("fonts", R.string.settings_category_fonts, R.string.settings_hint_fonts, listOf("font", "size", "weight", "قلم")),
    Gestures("gestures", R.string.settings_category_gestures, R.string.settings_hint_gestures, listOf("tap", "swipe", "brightness",
        "volume", "لمس")),
    Shadowing("shadowing", R.string.settings_category_shadowing, R.string.settings_hint_shadowing, listOf("repeat", "pause", "shadow",
        "تکرار")),
    Learning("learning", R.string.settings_category_learning, R.string.settings_hint_learning, listOf("level", "cefr", "popup", "word",
        "سطح")),
    Ai("ai", R.string.settings_category_ai, R.string.settings_hint_ai, listOf("openai", "gemini", "anthropic", "key", "prompt")),
    Translation("translation", R.string.settings_category_translation, R.string.settings_hint_translation, listOf("ml kit", "model",
        "cache")),
    Dictionary("dictionary", R.string.settings_category_dictionary, R.string.settings_hint_dictionary, listOf("dictionary", "offline",
        "فرهنگ")),
    QuickActions("quick", R.string.settings_category_quick_actions, R.string.settings_hint_quick_actions, listOf("button", "dock", "icon")),
    About("about", R.string.settings_category_about, R.string.settings_hint_about, listOf("version", "license", "github", "export",
        "import")),
    ;

    fun matches(text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return true
        return keywords.any { it.contains(needle, ignoreCase = true) }
    }

    companion object {
        val all = entries.toList()
    }
}


@Composable
private fun StatusBanner(status: SettingsViewModel.SettingsStatus, onDismiss: () -> Unit) {
    when (status) {
        is SettingsViewModel.SettingsStatus.Info -> InfoCard(status.text, MaterialTheme.colorScheme.secondaryContainer)
        is SettingsViewModel.SettingsStatus.Error -> InfoCard(status.text, MaterialTheme.colorScheme.errorContainer)
        is SettingsViewModel.SettingsStatus.Imported -> InfoCard(
            stringResource(R.string.settings_level_imported_count, status.count) +
                status.warnings.joinToString(prefix = " · ") { it },
            MaterialTheme.colorScheme.secondaryContainer,
        )

        is SettingsViewModel.SettingsStatus.ImportReported -> InfoCard(
            stringResource(R.string.settings_imported) + " · " + status.report.appliedSections.joinToString() +
                status.report.warnings.joinToString(prefix = " · "),
            MaterialTheme.colorScheme.secondaryContainer,
        )

        SettingsViewModel.SettingsStatus.Idle -> Unit
    }
}

@Composable
private fun InfoCard(text: String, background: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.xs)
            .background(background, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun AppearanceSection(settings: com.sublearn.core.settings.AppSettings, viewModel: SettingsViewModel) {
    val appearance = settings.appearance
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionCard(stringResource(R.string.settings_category_appearance)) {
        ChoiceRow(
            title = stringResource(R.string.settings_theme_mode),
            options = ThemeMode.entries,
            selected = appearance.themeMode,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(themeMode = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_accent),
            options = AccentToken.entries,
            selected = appearance.accent,
            label = { it.name.lowercase().replace('_', ' ') },
            onSelect = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(accent = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            value = appearance.useDynamicColor,
            onChange = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(useDynamicColor = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_reduce_motion),
            value = appearance.reduceMotion,
            onChange = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(reduceMotion = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.app_font_scale),
            value = appearance.appFontScale,
            range = 0.8f..1.6f,
            valueLabel = "%.2f".format(appearance.appFontScale),
            onValueChange = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(appFontScale = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_corner_radius),
            value = appearance.cornerRadiusDp,
            range = 0f..32f,
            valueLabel = "${appearance.cornerRadiusDp.toInt()} dp",
            onValueChange = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(cornerRadiusDp = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_show_ripples),
            value = appearance.showRipples,
            onChange = { value -> viewModel.update { it.copy(appearance = it.appearance.copy(showRipples = value)) } },
        )
    }
    SectionCard(stringResource(R.string.settings_language_ui)) {
        ChoiceRow(
            title = stringResource(R.string.settings_language_ui),
            options = listOf("system", "en", "fa"),
            selected = if (settings.languages.followSystemLocale) "system" else settings.languages.uiLocale,
            label = { if (it == "system") stringResource(R.string.settings_theme_system) else it },
            onSelect = { value ->
                viewModel.update { it.copy(languages = it.languages.copy(uiLocale = if (value == "system") "en" else value,
                    followSystemLocale = value == "system")) }
                context.applyLocale(value)
            },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_language_learning),
            options = listOf("en"),
            selected = settings.languages.learningLanguage,
            label = { it },
            onSelect = { value -> viewModel.update { it.copy(languages = it.languages.copy(learningLanguage = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_language_native),
            options = listOf("fa", "ar", "en"),
            selected = settings.languages.nativeLanguage,
            label = { it },
            onSelect = { value -> viewModel.update { it.copy(languages = it.languages.copy(nativeLanguage = value)) } },
        )
    }
}

private fun Context.applyLocale(tag: String) {
    val editor = getSharedPreferences("sublearn_locale", Context.MODE_PRIVATE).edit()
    if (tag == "system") editor.remove("lang") else editor.putString("lang", tag)
    editor.apply()
    (findActivity())?.recreate()
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        (ctx as? Activity)?.let { return it }
        ctx = ctx.baseContext
    }
    return null
}

