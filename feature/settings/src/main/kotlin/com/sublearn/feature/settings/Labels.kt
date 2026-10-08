package com.sublearn.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sublearn.core.designsystem.R
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.DetailsOpenTrigger
import com.sublearn.core.settings.DictionaryPreference
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.DoubleTapAction
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.GestureSlot
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.LevelProviderToken
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.settings.QuickActionColumn
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.ThemeMode
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.TranslationProviderToken
import com.sublearn.core.subtitles.TrackRole

/**
 * Labels for the settings enums.
 *
 * Two kinds of value appear here on purpose. Anything a user reasons about in words gets a
 * translated string; a technical identifier (an accent token, a font weight, a media mime name) is
 * shown as-is, because translating `A2` or `SEMIBOLD` would only make it harder to recognise.
 */

@Composable
fun TrackRole.label(): String = stringResource(
    if (this == TrackRole.LEARNING) R.string.subtitle_layer_learning else R.string.subtitle_layer_translation,
)

fun TrackRole.titleRes(): Int =
    if (this == TrackRole.LEARNING) R.string.subtitle_layer_learning else R.string.subtitle_layer_translation

@Composable
fun ThemeMode.label(): String = stringResource(labelRes())

fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.AMOLED -> R.string.settings_theme_amoled
}

fun OrientationLock.labelRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.settings_orientation_auto
    OrientationLock.PORTRAIT -> R.string.settings_orientation_portrait
    OrientationLock.LANDSCAPE -> R.string.settings_orientation_landscape
    OrientationLock.REVERSE_LANDSCAPE -> R.string.settings_orientation_reverse_landscape
    OrientationLock.SENSOR -> R.string.settings_orientation_sensor
}

fun DecoderMode.labelRes(): Int = when (this) {
    DecoderMode.SOFTWARE -> R.string.player_decoder_software
    DecoderMode.HARDWARE -> R.string.player_decoder_hardware
    DecoderMode.HARDWARE_FALLBACK -> R.string.player_decoder_hardware_plus
}

fun AspectMode.labelRes(): Int = when (this) {
    AspectMode.FIT -> R.string.player_aspect_fit
    AspectMode.FILL -> R.string.player_aspect_fill
    AspectMode.ZOOM -> R.string.player_aspect_zoom
    AspectMode.STRETCH -> R.string.player_aspect_stretch
    AspectMode.CUSTOM_RATIO -> R.string.player_aspect_custom
}

fun DoubleTapAction.labelRes(): Int = when (this) {
    DoubleTapAction.PAUSE -> R.string.settings_double_tap_pause
    DoubleTapAction.SEEK -> R.string.settings_double_tap_seek
    DoubleTapAction.NONE -> R.string.settings_double_tap_none
}

fun LearningMode.labelRes(): Int = when (this) {
    LearningMode.ENTERTAINMENT -> R.string.settings_mode_entertainment
    LearningMode.LEARNING -> R.string.settings_mode_learning
    LearningMode.OFF -> R.string.settings_mode_off
}

fun CefrLevel.labelRes(): Int = when (this) {
    CefrLevel.A1 -> R.string.level_a1
    CefrLevel.A2 -> R.string.level_a2
    CefrLevel.B1 -> R.string.level_b1
    CefrLevel.B2 -> R.string.level_b2
    CefrLevel.C1 -> R.string.level_c1
    CefrLevel.C2 -> R.string.level_c2
    CefrLevel.UNKNOWN -> R.string.level_unknown
}

fun GestureAction.labelRes(): Int = when (this) {
    GestureAction.NONE -> R.string.gesture_none
    GestureAction.BRIGHTNESS -> R.string.gesture_brightness
    GestureAction.VOLUME -> R.string.gesture_volume
    GestureAction.SEEK -> R.string.gesture_seek
    GestureAction.SEEK_FORWARD -> R.string.gesture_seek_forward
    GestureAction.SEEK_BACKWARD -> R.string.gesture_seek_backward
    GestureAction.TOGGLE_PLAY -> R.string.gesture_toggle_play
    GestureAction.TOGGLE_CONTROLS -> R.string.gesture_toggle_controls
    GestureAction.PLAYBACK_SPEED -> R.string.gesture_playback_speed
    GestureAction.ASPECT_RATIO -> R.string.gesture_aspect_ratio
    GestureAction.REPEAT_BLOCK -> R.string.gesture_repeat_block
    GestureAction.TOGGLE_LEARNING_SUBTITLE -> R.string.gesture_toggle_learning
    GestureAction.TOGGLE_TRANSLATION_SUBTITLE -> R.string.gesture_toggle_translation
    GestureAction.LOCK -> R.string.gesture_lock
    GestureAction.NEXT_BLOCK -> R.string.gesture_next_block
    GestureAction.PREVIOUS_BLOCK -> R.string.gesture_previous_block
    GestureAction.TOGGLE_MUTE -> R.string.gesture_toggle_mute
}

fun GestureSlot.labelRes(): Int = when (this) {
    GestureSlot.LEFT_VERTICAL -> R.string.slot_left_vertical
    GestureSlot.RIGHT_VERTICAL -> R.string.slot_right_vertical
    GestureSlot.HORIZONTAL -> R.string.slot_horizontal
    GestureSlot.DOUBLE_TAP_CENTER -> R.string.slot_double_tap_center
    GestureSlot.DOUBLE_TAP_LEFT -> R.string.slot_double_tap_left
    GestureSlot.DOUBLE_TAP_RIGHT -> R.string.slot_double_tap_right
    GestureSlot.TWO_FINGER_VERTICAL -> R.string.slot_two_finger_vertical
    GestureSlot.PINCH -> R.string.slot_pinch
}

fun SubtitleVerticalAnchor.labelRes(): Int = when (this) {
    SubtitleVerticalAnchor.TOP -> R.string.settings_anchor_top
    SubtitleVerticalAnchor.CENTER -> R.string.settings_anchor_center
    SubtitleVerticalAnchor.BOTTOM -> R.string.settings_anchor_bottom
}

fun SubtitleHorizontalAnchor.labelRes(): Int = when (this) {
    SubtitleHorizontalAnchor.START -> R.string.settings_anchor_start
    SubtitleHorizontalAnchor.CENTER -> R.string.settings_anchor_center
    SubtitleHorizontalAnchor.END -> R.string.settings_anchor_end
}

fun LayerStackDirection.labelRes(): Int = when (this) {
    LayerStackDirection.ABOVE -> R.string.settings_stack_above
    LayerStackDirection.BELOW -> R.string.settings_stack_below
}

fun LevelProviderToken.labelRes(): Int = when (this) {
    LevelProviderToken.MY_WORDS -> R.string.settings_level_source_my_words
    LevelProviderToken.MANUAL -> R.string.settings_level_source_manual
    LevelProviderToken.IMPORTED_FREQUENCY_LIST -> R.string.settings_level_source_imported
    LevelProviderToken.AUTOMATIC -> R.string.settings_level_source_automatic
}

fun DetailsOpenTrigger.labelRes(): Int = when (this) {
    DetailsOpenTrigger.ICON -> R.string.details_open_icon
    DetailsOpenTrigger.LONG_PRESS -> R.string.details_open_long_press
    DetailsOpenTrigger.DOUBLE_TAP -> R.string.details_open_double_tap
}

fun DictionaryPreference.labelRes(): Int = when (this) {
    DictionaryPreference.OFFLINE_FIRST -> R.string.dict_offline_first
    DictionaryPreference.WEB_TRANSLATE_ONLY -> R.string.dict_web_only
    DictionaryPreference.OFFLINE_ONLY -> R.string.dict_offline_only
}

fun DockMode.labelRes(): Int = when (this) {
    DockMode.BAR -> R.string.settings_dock_bar
    DockMode.FLOATING -> R.string.settings_dock_floating
    DockMode.HIDDEN -> R.string.settings_dock_hidden
}

fun QuickActionColumn.labelRes(): Int = when (this) {
    QuickActionColumn.TOP_LEFT -> R.string.settings_column_top_left
    QuickActionColumn.TOP_RIGHT -> R.string.settings_column_top_right
    QuickActionColumn.BOTTOM_LEFT -> R.string.settings_column_bottom_left
    QuickActionColumn.BOTTOM_RIGHT -> R.string.settings_column_bottom_right
}

fun AiProviderToken.labelRes(): Int = when (this) {
    AiProviderToken.GEMINI -> R.string.ai_provider_gemini
    AiProviderToken.OPENAI -> R.string.ai_provider_openai
    AiProviderToken.ANTHROPIC -> R.string.ai_provider_anthropic
    AiProviderToken.CUSTOM -> R.string.ai_provider_custom
}

fun TranslationProviderToken.labelRes(): Int = when (this) {
    TranslationProviderToken.ML_KIT_ON_DEVICE -> R.string.settings_translation_mlkit
    TranslationProviderToken.OFF_STUB -> R.string.settings_mode_off
}

/** The section list entry for a route key. */
fun String.settingsTitleRes(): Int = when (this) {
    "appearance" -> R.string.settings_category_appearance
    "player" -> R.string.settings_category_player
    "subtitles" -> R.string.settings_category_subtitles
    "fonts" -> R.string.settings_category_fonts
    "gestures" -> R.string.settings_category_gestures
    "shadowing" -> R.string.settings_category_shadowing
    "learning" -> R.string.settings_category_learning
    "ai" -> R.string.settings_category_ai
    "translation" -> R.string.settings_category_translation
    "dictionary" -> R.string.settings_category_dictionary
    "quick" -> R.string.settings_category_quick_actions
    "prompt" -> R.string.settings_prompt_title
    "about" -> R.string.settings_category_about
    else -> R.string.settings_title
}
