package com.sublearn.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.R
import com.sublearn.core.player.OpenDocumentContract
import com.sublearn.core.player.OpenTreeContract
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.DetailsOpenTrigger
import com.sublearn.core.settings.DictionaryPreference
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.DoubleTapAction
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.GestureSlot
import com.sublearn.core.settings.LayerStackDirection
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.LevelProviderToken
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.settings.QuickActionColumn
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.settings.WordStyle
import com.sublearn.core.subtitles.NormalizerConfig
import com.sublearn.core.subtitles.TrackRole

/** Player behaviour (PLY-8, ENGINEERING: every control here has a real effect). */
@Composable
fun PlayerSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val player = settings.player
    SectionCard(stringResource(R.string.settings_category_player)) {
        StepperRow(
            title = stringResource(R.string.settings_seeking),
            value = (player.seekStepMs / 1000L).toInt(),
            range = 1..60,
            step = 1,
            suffix = " s",
            onChange = { seconds -> viewModel.update { it.copy(player = it.player.copy(seekStepMs = seconds * 1000L)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_swipe_seek),
            value = player.swipeSeekSecondsPerScreen,
            range = 10..600,
            step = 10,
            suffix = " s",
            onChange = { seconds -> viewModel.update { it.copy(player = it.player.copy(swipeSeekSecondsPerScreen = seconds)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_double_tap),
            options = DoubleTapAction.entries,
            selected = player.doubleTapAction,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(player = it.player.copy(doubleTapAction = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_seeking),
            value = (player.doubleTapSeekMs / 1000L).toInt(),
            range = 5..120,
            step = 5,
            suffix = " s",
            onChange = { seconds -> viewModel.update { it.copy(player = it.player.copy(doubleTapSeekMs = seconds * 1000L)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_auto_hide),
            value = player.controlsAutoHideMs.toFloat() / 1000f,
            range = 1f..10f,
            valueLabel = "%.1f s".format(player.controlsAutoHideMs / 1000f),
            onValueChange = { value ->
                viewModel.update { it.copy(player = it.player.copy(controlsAutoHideMs = (value * 1000).toLong())) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_orientation),
            options = OrientationLock.entries,
            selected = player.orientationLock,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(player = it.player.copy(orientationLock = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.player_decoder),
            options = DecoderMode.entries,
            selected = player.decoder,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(player = it.player.copy(decoder = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.player_aspect),
            options = AspectMode.entries,
            selected = player.aspect,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(player = it.player.copy(aspect = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_default_speed),
            value = player.defaultSpeedPercent,
            range = player.speedMinPercent..player.speedMaxPercent,
            step = 5,
            suffix = "%",
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(defaultSpeedPercent = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_speed_step),
            value = player.speedStepPercent,
            range = 5..100,
            step = 5,
            suffix = "%",
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(speedStepPercent = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_speed_range),
            value = player.speedMinPercent,
            range = 10..90,
            step = 5,
            suffix = "%",
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(speedMinPercent = value)) } },
        )
    }
    SectionCard(stringResource(R.string.settings_speed_badge)) {
        SwitchRow(
            title = stringResource(R.string.settings_two_finger_speed),
            value = player.twoFingerSpeedShortcut,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(twoFingerSpeedShortcut = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_buffer_indicator),
            value = player.showBufferIndicator,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(showBufferIndicator = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_speed_badge),
            value = player.showSpeedBadge,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(showSpeedBadge = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_show_controls_paused),
            value = player.showControlsWhilePaused,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(showControlsWhilePaused = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            value = player.keepScreenOn,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(keepScreenOn = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_remember_position),
            value = player.rememberPosition,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(rememberPosition = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_pip_on_leave),
            value = player.enterPipOnLeave,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(enterPipOnLeave = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_lock_gestures),
            value = player.lockGesturesWhenLocked,
            onChange = { value -> viewModel.update { it.copy(player = it.player.copy(lockGesturesWhenLocked = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_dim_overlay),
            value = player.dimOverlayPercent.toFloat(),
            range = 0f..70f,
            valueLabel = "${player.dimOverlayPercent}%",
            onValueChange = { value -> viewModel.update { it.copy(player = it.player.copy(dimOverlayPercent = value.toInt())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_safe_margin),
            value = player.subtitleSafeMarginDp,
            range = 0f..64f,
            valueLabel = "${player.subtitleSafeMarginDp.toInt()} dp",
            onValueChange = { value -> viewModel.update { it.copy(player = it.player.copy(subtitleSafeMarginDp = value)) } },
        )
    }
}

/** Both layers, the list and the cleaning rules (SUB-1..SUB-6). */
@Composable
fun SubtitlesSection(settings: AppSettings, viewModel: SettingsViewModel, context: android.content.Context) {
    val subtitles = settings.subtitles
    TrackRole.entries.forEach { role ->
        val layer = subtitles.layer(role)
        SectionCard(stringResource(role.titleRes())) {
            SwitchRow(
                title = stringResource(R.string.settings_layer_visible),
                value = layer.visible,
                onChange = { value -> viewModel.updateLayer(role) { it.copy(visible = value) } },
            )
            SliderRow(
                title = stringResource(R.string.subtitle_delay),
                value = layer.delayMs.toFloat(),
                range = -5000f..5000f,
                steps = 99,
                valueLabel = "${layer.delayMs} ms",
                onValueChange = { value -> viewModel.updateLayer(role) { it.copy(delayMs = value.toLong()) } },
            )
            SliderRow(
                title = stringResource(R.string.subtitle_size),
                value = layer.scalePercent.toFloat(),
                range = 60f..220f,
                valueLabel = "${layer.scalePercent}%",
                onValueChange = { value -> viewModel.updateLayer(role) { it.copy(scalePercent = value.toInt()) } },
            )
            SliderRow(
                title = stringResource(R.string.subtitle_transparency),
                value = layer.transparencyPercent.toFloat(),
                range = 0f..100f,
                valueLabel = "${layer.transparencyPercent}%",
                onValueChange = { value -> viewModel.updateLayer(role) { it.copy(transparencyPercent = value.toInt()) } },
            )
            ChoiceRow(
                title = stringResource(R.string.subtitle_position),
                options = SubtitleVerticalAnchor.entries,
                selected = layer.placement.vertical,
                label = { stringResource(it.labelRes()) },
                onSelect = { value ->
                    viewModel.updateLayer(role) { it.copy(placement = it.placement.copy(vertical = value)) }
                },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_font_surface),
                options = SubtitleHorizontalAnchor.entries,
                selected = layer.placement.horizontal,
                label = { stringResource(it.labelRes()) },
                onSelect = { value ->
                    viewModel.updateLayer(role) { it.copy(placement = it.placement.copy(horizontal = value)) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.settings_show_in_list),
                value = layer.showInList,
                onChange = { value -> viewModel.updateLayer(role) { it.copy(showInList = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.subtitle_tool_line_breaks),
                value = !layer.keepOriginalLineBreaks,
                onChange = { value -> viewModel.updateLayer(role) { it.copy(keepOriginalLineBreaks = !value) } },
            )
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = layer.externalFileKeys.joinToString().ifBlank { stringResource(R.string.subtitle_list_empty) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val filePicker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("text/*",
                "application/octet-stream"))) { picked ->
                if (picked != null) {
                    viewModel.update { it.copy(subtitles = it.subtitles.copy(sidecarTreeUri = it.subtitles.sidecarTreeUri)) }
                }
            }
            ActionRow(
                title = stringResource(R.string.subtitle_add_file),
                onClick = { filePicker.launch(arrayOf("text/*")) },
                subtitle = stringResource(R.string.subtitle_list_empty),
            )
        }
    }

    SectionCard(stringResource(R.string.settings_list_panel)) {
        SwitchRow(
            title = stringResource(R.string.settings_list_panel),
            value = subtitles.listPanelEnabled,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(listPanelEnabled = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_list_width),
            value = subtitles.listPanelWidthPercent.toFloat(),
            range = 20f..60f,
            valueLabel = "${subtitles.listPanelWidthPercent}%",
            onValueChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(listPanelWidthPercent = value.toInt())) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_list_auto_scroll),
            value = subtitles.listAutoScroll,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(listAutoScroll = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_highlight_current),
            value = subtitles.highlightCurrentBlock,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(highlightCurrentBlock = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.subtitle_list_no_spoiler),
            value = subtitles.noSpoilerMode,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(noSpoilerMode = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_language_badge),
            value = subtitles.showLanguageBadge,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(showLanguageBadge = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_max_lines),
            value = subtitles.maxLinesPerLayer,
            range = 1..6,
            step = 1,
            onChange = { value -> viewModel.update { it.copy(subtitles = it.subtitles.copy(maxLinesPerLayer = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_reset),
            options = LayerStackDirection.entries,
            selected = subtitles.layer(TrackRole.LEARNING).stackDirection,
            label = { stringResource(it.labelRes()) },
            onSelect = { value ->
                viewModel.updateLayer(TrackRole.LEARNING) { it.copy(stackDirection = value) }
            },
        )
        val treePicker = rememberLauncherForActivityResult(OpenTreeContract()) { tree ->
            viewModel.update { it.copy(subtitles = it.subtitles.copy(sidecarTreeUri = tree)) }
        }
        ActionRow(
            title = stringResource(R.string.settings_sidecar_folder),
            subtitle = subtitles.sidecarTreeUri ?: stringResource(R.string.settings_sidecar_folder_none),
            onClick = { treePicker.launch(Unit) },
        )
    }

    SectionCard(stringResource(R.string.subtitle_tools)) {
        val normalizer = subtitles.normalizer
        // One helper for the cleaning rules, so each row states only the value it owns.
        val applyNormalizerConfig: (NormalizerConfig.() -> NormalizerConfig) -> Unit = { transform ->
            viewModel.update { settings ->
                settings.copy(subtitles = settings.subtitles.copy(normalizer = transform(settings.subtitles.normalizer)))
            }
        }
        SliderRow(
            title = stringResource(R.string.settings_merge_gap),
            value = normalizer.mergeGapMs.toFloat(),
            range = 0f..3000f,
            valueLabel = "${normalizer.mergeGapMs} ms",
            onValueChange = { value ->
                applyNormalizerConfig { copy(mergeGapMs = value.toLong()) }
            },
        )
        SliderRow(
            title = stringResource(R.string.settings_max_block_duration),
            value = normalizer.maxBlockDurationMs.toFloat(),
            range = 1500f..15000f,
            valueLabel = "%.1f s".format(normalizer.maxBlockDurationMs / 1000f),
            onValueChange = { value ->
                applyNormalizerConfig { copy(maxBlockDurationMs = value.toLong()) }
            },
        )
        SliderRow(
            title = stringResource(R.string.subtitle_tool_max_chars),
            value = normalizer.maxBlockChars.toFloat(),
            range = 40f..200f,
            valueLabel = normalizer.maxBlockChars.toString(),
            onValueChange = { value ->
                applyNormalizerConfig { copy(maxBlockChars = value.toInt()) }
            },
        )
        SliderRow(
            title = stringResource(R.string.settings_max_lines),
            value = normalizer.maxCharsPerLine.toFloat(),
            range = 20f..80f,
            valueLabel = normalizer.maxCharsPerLine.toString(),
            onValueChange = { value ->
                applyNormalizerConfig { copy(maxCharsPerLine = value.toInt()) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.settings_sentence_split),
            value = normalizer.breakOnSentenceEnd,
            onChange = { value ->
                applyNormalizerConfig { copy(breakOnSentenceEnd = value) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.settings_drop_duplicates),
            value = normalizer.dropDuplicateNeighbours,
            onChange = { value ->
                applyNormalizerConfig { copy(dropDuplicateNeighbours = value) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.settings_clamp_overlaps),
            value = normalizer.clampOverlaps,
            onChange = { value ->
                applyNormalizerConfig { copy(clampOverlaps = value) }
            },
        )
    }
}

/** Gesture → action mapping (PLY-2). */
@Composable
fun GesturesSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val gestures = settings.gestures
    SectionCard(stringResource(R.string.settings_category_gestures)) {
        GestureSlot.entries.forEach { slot ->
            ChoiceRow(
                title = stringResource(slot.labelRes()),
                options = GestureAction.entries,
                selected = gestures.actionFor(slot),
                label = { stringResource(it.labelRes()) },
                onSelect = { value ->
                    viewModel.update { it.copy(gestures = it.gestures.withAction(slot, value)) }
                },
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_two_finger_speed),
            value = gestures.actionFor(GestureSlot.TWO_FINGER_VERTICAL) == GestureAction.PLAYBACK_SPEED,
            onChange = { value ->
                viewModel.update {
                    it.copy(
                        gestures = it.gestures.withAction(
                            GestureSlot.TWO_FINGER_VERTICAL,
                            if (value) GestureAction.PLAYBACK_SPEED else GestureAction.NONE,
                        ),
                    )
                }
            },
        )
        SwitchRow(
            title = stringResource(R.string.settings_pinch_aspect),
            value = gestures.actionFor(GestureSlot.PINCH) == GestureAction.ASPECT_RATIO,
            onChange = { value ->
                viewModel.update {
                    it.copy(
                        gestures = it.gestures.withAction(
                            GestureSlot.PINCH,
                            if (value) GestureAction.ASPECT_RATIO else GestureAction.NONE,
                        ),
                    )
                }
            },
        )
        SliderRow(
            title = stringResource(R.string.settings_gesture_safe),
            value = gestures.systemGestureSafeDp,
            range = 0f..64f,
            valueLabel = "${gestures.systemGestureSafeDp.toInt()} dp",
            onValueChange = { value -> viewModel.update { it.copy(gestures = it.gestures.copy(systemGestureSafeDp = value)) } },
        )
    }
}

/** Shadowing (SHD-1..SHD-3). */
@Composable
fun ShadowingSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val shadowing = settings.shadowing
    SectionCard(stringResource(R.string.settings_category_shadowing)) {
        StepperRow(
            title = stringResource(R.string.settings_repeat_count),
            value = shadowing.repeatCount,
            range = 0..12,
            step = 1,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(repeatCount = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_pause_base),
            value = shadowing.pauseBaseMs.toFloat(),
            range = 0f..2000f,
            valueLabel = "${shadowing.pauseBaseMs} ms",
            onValueChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(pauseBaseMs = value.toLong())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_pause_multiplier),
            value = shadowing.pauseDurationMultiplier,
            range = 0f..1.5f,
            valueLabel = "%.2f".format(shadowing.pauseDurationMultiplier),
            onValueChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(pauseDurationMultiplier = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_pause_max),
            value = shadowing.pauseMaxMs.toFloat(),
            range = 500f..10000f,
            valueLabel = "%.1f s".format(shadowing.pauseMaxMs / 1000f),
            onValueChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(pauseMaxMs = value.toLong())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_pre_roll),
            value = shadowing.preRollMs.toFloat(),
            range = 0f..1500f,
            valueLabel = "${shadowing.preRollMs} ms",
            onValueChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(preRollMs = value.toLong())) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_min_block_duration),
            value = (shadowing.minBlockDurationMs / 100L).toInt(),
            range = 1..30,
            step = 1,
            suffix = " x100 ms",
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(minBlockDurationMs = value * 100L)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_auto_repeat),
            value = shadowing.autoRepeatEnabled,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(autoRepeatEnabled = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_repeat_every),
            value = shadowing.repeatOnEveryBlock,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(repeatOnEveryBlock = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_stop_at_end_default),
            value = shadowing.stopAtEndOfBlock,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(stopAtEndOfBlock = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_hold_invert),
            value = shadowing.holdInvertsTemporarily,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(holdInvertsTemporarily = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_show_repeat_ring),
            value = shadowing.showRepeatRing,
            onChange = { value -> viewModel.update { it.copy(shadowing = it.shadowing.copy(showRepeatRing = value)) } },
        )
    }
}

/** Learning mode, level and word colours (LRN-2..LRN-5). */
@Composable
fun LearningSection(settings: AppSettings, viewModel: SettingsViewModel, context: android.content.Context) {
    val learning = settings.learning
    val level = settings.level
    SectionCard(stringResource(R.string.settings_learning_mode)) {
        ChoiceRow(
            title = stringResource(R.string.settings_learning_mode),
            options = LearningMode.entries,
            selected = learning.mode,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(learning = it.learning.copy(mode = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_level),
            options = CefrLevel.entries,
            selected = learning.manualLevel,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(learning = it.learning.copy(manualLevel = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_show_above_only),
            value = learning.showAboveLevelOnly,
            onChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(showAboveLevelOnly = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_marked_known),
            value = learning.markedWordsAreKnown,
            onChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(markedWordsAreKnown = value)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_popup_count),
            value = learning.maxPopupCards,
            range = 0..10,
            step = 1,
            onChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(maxPopupCards = value)) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_popup_lifetime),
            value = learning.popupLifetimeMs.toFloat(),
            range = 1000f..20000f,
            valueLabel = "%.1f s".format(learning.popupLifetimeMs / 1000f),
            onValueChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(popupLifetimeMs = value.toLong())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_popup_opacity),
            value = learning.popupOpacityPercent.toFloat(),
            range = 30f..100f,
            valueLabel = "${learning.popupOpacityPercent}%",
            onValueChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(popupOpacityPercent = value.toInt())) } },
        )
        SliderRow(
            title = stringResource(R.string.settings_popup_rise),
            value = learning.popupRiseDp,
            range = 0f..80f,
            valueLabel = "${learning.popupRiseDp.toInt()} dp",
            onValueChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(popupRiseDp = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_tap_counts),
            value = learning.tapCountsForLineAndBlock,
            onChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(tapCountsForLineAndBlock = value)) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_multiword),
            value = learning.multiWordSelection,
            onChange = { value -> viewModel.update { it.copy(learning = it.learning.copy(multiWordSelection = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.word_details),
            options = DetailsOpenTrigger.entries,
            selected = learning.openDetailsWith,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(learning = it.learning.copy(openDetailsWith = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_word_colors),
            options = DictionaryPreference.entries,
            selected = learning.dictionaryPreference,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(learning = it.learning.copy(dictionaryPreference = value)) } },
        )
    }

    SectionCard(stringResource(R.string.menu_level)) {
        ChoiceRow(
            title = stringResource(R.string.settings_level_provider),
            options = LevelProviderToken.entries,
            selected = level.provider,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(level = it.level.copy(provider = value)) } },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_assumed_level),
            options = CefrLevel.entries.filter { it != CefrLevel.UNKNOWN },
            selected = level.assumedLevelOfUnknownWords,
            label = { stringResource(it.labelRes()) },
            onSelect = { value -> viewModel.update { it.copy(level = it.level.copy(assumedLevelOfUnknownWords = value)) } },
        )
        val listPicker = rememberLauncherForActivityResult(OpenDocumentContract(arrayOf("text/plain", "text/*"))) { picked ->
            if (picked != null) viewModel.importWordList(picked.uri)
        }
        ActionRow(title = stringResource(R.string.settings_level_import), subtitle = stringResource(R.string.settings_level_import_hint),
            onClick = { listPicker.launch(arrayOf("text/plain")) })
        val state = viewModel.levelState()
        Text(
            text = state.details(context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow(title = stringResource(R.string.action_remove), onClick = viewModel::clearWordList)
    }

    SectionCard(stringResource(R.string.settings_word_colors)) {
        val styles = settings.wordStyles
        SwitchRow(
            title = stringResource(R.string.settings_word_colors),
            value = styles.enabled,
            onChange = { value -> viewModel.update { it.copy(wordStyles = it.wordStyles.copy(enabled = value)) } },
        )
        WordStyleRow(stringResource(R.string.settings_category_learning), styles.myWords) { value ->
            viewModel.update { it.copy(wordStyles = it.wordStyles.copy(myWords = value)) }
        }
        WordStyleRow(stringResource(R.string.settings_assumed_level), styles.unknownAboveLevel) { value ->
            viewModel.update { it.copy(wordStyles = it.wordStyles.copy(unknownAboveLevel = value)) }
        }
        WordStyleRow(stringResource(R.string.translate_line), styles.phrases) { value ->
            viewModel.update { it.copy(wordStyles = it.wordStyles.copy(phrases = value)) }
        }
    }
}

@Composable
private fun WordStyleRow(title: String, style: WordStyle, onChange: (WordStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.xs)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            title = stringResource(R.string.settings_font_color),
            options = listOf(null, 0xFFFFD98AL, 0xFF7BE3A1L, 0xFF8FC8FFL, 0xFFFFA3A3L),
            selected = style.colorArgb,
            label = { it?.let { value -> "%08X".format(value) } ?: stringResource(R.string.settings_mode_off) },
            onSelect = { value -> onChange(style.copy(colorArgb = value)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_font_weight),
            value = style.bold,
            onChange = { value -> onChange(style.copy(bold = value)) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_font_italic),
            value = style.italic,
            onChange = { value -> onChange(style.copy(italic = value)) },
        )
    }
}
