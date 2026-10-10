package com.sublearn.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.player.PlayerTrackType
import com.sublearn.core.player.PlaylistRepeat
import com.sublearn.core.player.TrackInfo
import com.sublearn.core.player.TrackRef
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.FontSpec
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.subtitles.TrackRole

/** Milliseconds per click of the subtitle delay buttons. */
private const val DELAY_STEP_MS = 100L

/** Lower and upper bound of the subtitle size slider, in percent. */
private const val SIZE_MIN_PERCENT = 60f
private const val SIZE_MAX_PERCENT = 220f

/** Subtitle transparency range, in percent. */
private const val TRANSPARENCY_MAX_PERCENT = 100f

/** Speed presets offered as chips under the speed slider. */
private val SPEED_PRESETS = listOf(50, 75, 100, 125, 150)

/** Hosts every bottom sheet the player offers; one place decides which one is open (PLY-3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsHost(
    ui: PlayerUi,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ui.sheet == Sheet.NONE) return
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(horizontal = Dimens.lg)
                .padding(bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            when (ui.sheet) {
                Sheet.TRACKS -> TracksSheet(ui, viewModel::selectTrack, viewModel::clearTrackOverride)
                Sheet.SPEED -> SpeedSheet(ui, viewModel::setSpeedPercent)
                Sheet.ASPECT -> AspectSheet(ui, viewModel::setAspect, viewModel::setCustomAspect)
                Sheet.DECODER -> DecoderSheet(ui, viewModel::setDecoder)
                Sheet.LAYER_OPTIONS -> LayerOptionsSheet(ui, viewModel)
                Sheet.TOOLS -> ToolsSheet(ui, viewModel)
                Sheet.PLAYLIST -> PlaylistSheet(ui, viewModel)
                Sheet.NONE -> Unit
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * One choice in a list. It is a radio button for TalkBack, with a check mark for the chosen one, and
 * its touch target is at least the minimum size.
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = LocalSubLearnColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.controlActive,
                modifier = Modifier.size(Dimens.inlineIcon),
            )
        } else {
            Spacer(Modifier.size(Dimens.inlineIcon))
        }
        Spacer(Modifier.size(Dimens.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.controlIdle)
        }
    }
}

@Composable
private fun TracksSheet(
    ui: PlayerUi,
    onSelect: (TrackRef) -> Unit,
    onClear: (PlayerTrackType) -> Unit,
) {
    SheetTitle(stringResource(R.string.player_subtitle_tracks))
    TrackGroup(stringResource(R.string.player_audio_tracks), ui.playback.audioTracks, onSelect, onClear)
    TrackGroup(stringResource(R.string.player_subtitle_tracks), ui.playback.textTracks, onSelect, onClear)
    if (ui.playback.audioTracks.isEmpty() && ui.playback.textTracks.isEmpty()) {
        Text(stringResource(R.string.player_no_tracks), style = MaterialTheme.typography.bodySmall)
    }
    Text(
        stringResource(R.string.subtitle_embedded_live),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrackGroup(
    title: String,
    tracks: List<TrackInfo>,
    onSelect: (TrackRef) -> Unit,
    onClear: (PlayerTrackType) -> Unit,
) {
    if (tracks.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { onClear(tracks.first().ref.type) }) {
            Text(stringResource(R.string.player_track_auto))
        }
    }
    tracks.forEach { track ->
        ChoiceRow(
            label = track.displayLabel,
            selected = track.isSelected,
            onClick = { onSelect(track.ref) },
            trailing = track.language,
        )
    }
}

@Composable
private fun SpeedSheet(ui: PlayerUi, onSet: (Int) -> Unit) {
    val player = ui.settings.player
    val percent = ui.playback.speedPercent
    SheetTitle(stringResource(R.string.player_speed))
    Text(stringResource(R.string.player_speed_value, percent), style = MaterialTheme.typography.headlineSmall)
    Slider(
        value = percent.toFloat(),
        onValueChange = { onSet(it.toInt()) },
        valueRange = player.speedMinPercent.toFloat()..player.speedMaxPercent.toFloat(),
        steps = speedSteps(player.speedMinPercent, player.speedMaxPercent, player.speedStepPercent),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        SPEED_PRESETS.forEach { value ->
            FilterChip(
                selected = percent == value,
                onClick = { onSet(value) },
                label = { Text(stringResource(R.string.player_speed_value, value)) },
            )
        }
    }
}

/** Discrete slider stops between the two bounds; never negative, which the Slider would reject. */
private fun speedSteps(minPercent: Int, maxPercent: Int, stepPercent: Int): Int {
    if (stepPercent <= 0 || maxPercent <= minPercent) return 0
    return ((maxPercent - minPercent) / stepPercent - 1).coerceAtLeast(0)
}

@Composable
private fun AspectSheet(ui: PlayerUi, onSet: (AspectMode) -> Unit, onCustom: (Int, Int) -> Unit) {
    SheetTitle(stringResource(R.string.player_aspect))
    AspectMode.entries.filter { it != AspectMode.CUSTOM_RATIO }.forEach { mode ->
        ChoiceRow(
            label = stringResource(mode.labelRes()),
            selected = ui.playback.aspect == mode,
            onClick = { onSet(mode) },
        )
    }
    var width by remember { mutableFloatStateOf(ui.settings.player.customAspectWidth.toFloat()) }
    var height by remember { mutableFloatStateOf(ui.settings.player.customAspectHeight.toFloat()) }
    Text(stringResource(R.string.player_aspect_custom), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        Slider(value = width, onValueChange = { width = it }, valueRange = ASPECT_MIN..ASPECT_MAX, modifier = Modifier.weight(1f))
        Slider(value = height, onValueChange = { height = it }, valueRange = ASPECT_MIN..ASPECT_MAX, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCustom(width.toInt(), height.toInt()) }) { Text(stringResource(R.string.action_apply)) }
    }
    Text(
        text = "%.2f : 1".format(width / height),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Custom aspect sides, in units. */
private const val ASPECT_MIN = 4f
private const val ASPECT_MAX = 32f

@Composable
internal fun TrackRole.title(): String = stringResource(
    if (this == TrackRole.LEARNING) R.string.subtitle_layer_learning else R.string.subtitle_layer_translation,
)

internal fun AspectMode.labelRes(): Int = when (this) {
    AspectMode.FIT -> R.string.player_aspect_fit
    AspectMode.FILL -> R.string.player_aspect_fill
    AspectMode.ZOOM -> R.string.player_aspect_zoom
    AspectMode.STRETCH -> R.string.player_aspect_stretch
    AspectMode.CUSTOM_RATIO -> R.string.player_aspect_custom
}

@Composable
private fun DecoderSheet(ui: PlayerUi, onSet: (DecoderMode) -> Unit) {
    SheetTitle(stringResource(R.string.player_decoder))
    DecoderMode.entries.forEach { mode ->
        ChoiceRow(
            label = stringResource(mode.labelRes()),
            selected = ui.settings.player.decoder == mode,
            onClick = { onSet(mode) },
        )
    }
    Text(
        stringResource(R.string.player_decoder_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun DecoderMode.labelRes(): Int = when (this) {
    DecoderMode.SOFTWARE -> R.string.player_decoder_software
    DecoderMode.HARDWARE -> R.string.player_decoder_hardware
    DecoderMode.HARDWARE_PLUS -> R.string.player_decoder_hardware_plus
}

internal fun PlaylistRepeat.labelRes(): Int = when (this) {
    PlaylistRepeat.OFF -> R.string.player_repeat_off
    PlaylistRepeat.ONE -> R.string.player_repeat_one
    PlaylistRepeat.ALL -> R.string.player_repeat_all
}

/** Per-layer controls for delay, size, transparency and the file behind the layer (SUB-3). */
@Composable
private fun LayerOptionsSheet(ui: PlayerUi, viewModel: PlayerViewModel) {
    var role by remember { mutableStateOf(TrackRole.LEARNING) }
    val settings = ui.settings.subtitleLayer(role)
    val layer = ui.layer(role)
    SheetTitle(stringResource(R.string.settings_category_subtitles))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TrackRole.entries.forEach { entry ->
            FilterChip(
                selected = role == entry,
                onClick = { role = entry },
                label = { Text(entry.title()) },
            )
        }
    }
    Text(stringResource(R.string.subtitle_delay), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        IconButton(onClick = { viewModel.setLayerDelay(role, -DELAY_STEP_MS) }) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.subtitle_delay_earlier))
        }
        Text(
            stringResource(R.string.subtitle_delay_value, settings.delayMs),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { viewModel.setLayerDelay(role, DELAY_STEP_MS) }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.subtitle_delay_later))
        }
        TextButton(onClick = { viewModel.updateLayer(role) { it.copy(delayMs = 0L) } }) {
            Text(stringResource(R.string.action_reset))
        }
    }
    Text(stringResource(R.string.subtitle_size), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = settings.scalePercent.toFloat(),
        onValueChange = { viewModel.setLayerScale(role, it.toInt()) },
        valueRange = SIZE_MIN_PERCENT..SIZE_MAX_PERCENT,
    )
    Text(stringResource(R.string.subtitle_transparency), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = settings.transparencyPercent.toFloat(),
        onValueChange = { viewModel.setLayerTransparency(role, it.toInt()) },
        valueRange = 0f..TRANSPARENCY_MAX_PERCENT,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        FilterChip(
            selected = settings.visible,
            onClick = { viewModel.toggleLayerVisible(role) },
            label = { Text(stringResource(R.string.player_layer_visible)) },
        )
        FilterChip(
            selected = settings.showInList,
            onClick = { viewModel.toggleListFor(role) },
            label = { Text(stringResource(R.string.subtitle_list)) },
        )
    }
    Text(
        text = layer.fileName?.let { name -> "$name · ${layer.charsetName.orEmpty()}" } ?: stringResource(R.string.subtitle_list_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TextButton(onClick = { viewModel.setLayoutMode(true) }) {
            Text(stringResource(R.string.subtitle_layout_mode))
        }
        layer.fileName?.let { name ->
            TextButton(onClick = {
                viewModel.removeFile(role, settings.externalFileKeys.firstOrNull() ?: name)
            }) {
                Text(stringResource(R.string.subtitle_remove_file))
            }
        }
    }
    layer.warnings.forEach { warning ->
        Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Batch tools over the loaded file (SUB-6): line breaks and block size, applied on demand. */
@Composable
private fun ToolsSheet(ui: PlayerUi, viewModel: PlayerViewModel) {
    val saved = ui.settings.subtitles.normalizer
    var removeBreaks by remember { mutableStateOf(saved.removeLineBreaks) }
    var maxChars by remember { mutableFloatStateOf(saved.maxBlockChars.toFloat()) }
    var mergeGap by remember { mutableFloatStateOf(saved.mergeGapMs.toFloat()) }
    SheetTitle(stringResource(R.string.subtitle_tools))
    FilterChip(
        selected = removeBreaks,
        onClick = { removeBreaks = !removeBreaks },
        label = { Text(stringResource(R.string.subtitle_tool_line_breaks)) },
    )
    Text(stringResource(R.string.subtitle_tool_max_chars), style = MaterialTheme.typography.labelLarge)
    Slider(value = maxChars, onValueChange = { maxChars = it }, valueRange = MAX_CHARS_MIN..MAX_CHARS_MAX)
    Text("${maxChars.toInt()}", style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.subtitle_delay), style = MaterialTheme.typography.labelLarge)
    Slider(value = mergeGap, onValueChange = { mergeGap = it }, valueRange = MERGE_GAP_MIN..MERGE_GAP_MAX)
    Text(stringResource(R.string.subtitle_delay_value, mergeGap.toLong()), style = MaterialTheme.typography.bodyLarge)
    TextButton(onClick = {
        viewModel.applyNormalizer(
            saved.copy(
                removeLineBreaks = removeBreaks,
                maxBlockChars = maxChars.toInt(),
                mergeGapMs = mergeGap.toLong(),
            ),
        )
    }) {
        Text(stringResource(R.string.subtitle_tool_apply))
    }
}

/** Bounds of the batch tool sliders. */
private const val MAX_CHARS_MIN = 40f
private const val MAX_CHARS_MAX = 160f
private const val MERGE_GAP_MIN = 0f
private const val MERGE_GAP_MAX = 2_000f

/**
 * The playlist: the entries with the playing one highlighted, and the repeat mode. Jumping between
 * entries is done with the playlist controls, so the rows are read-only.
 */
@Composable
private fun PlaylistSheet(ui: PlayerUi, viewModel: PlayerViewModel) {
    SheetTitle(stringResource(R.string.player_playlist))
    ui.playback.playlist.forEachIndexed { index, target ->
        val current = index == ui.playback.playlistIndex
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.touchTarget)
                .padding(horizontal = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = if (current) LocalSubLearnColors.current.controlActive else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = Dimens.lg),
            )
            Spacer(Modifier.size(Dimens.sm))
            Text(
                text = target.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) LocalSubLearnColors.current.controlActive else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        PlaylistRepeat.entries.forEach { repeat ->
            FilterChip(
                selected = ui.playback.repeat == repeat,
                onClick = { viewModel.setRepeat(repeat) },
                label = { Text(stringResource(repeat.labelRes())) },
            )
        }
    }
}

/**
 * The subtitle list (PLY-6, SUB-8): search, tap to seek, and no-spoiler. Only file-backed layers can
 * offer it, and an embedded track says so instead of showing an empty panel.
 *
 * In landscape it is a panel on the right; in portrait it sits below the picture ([landscape]).
 */
@Composable
internal fun SubtitleListPanel(
    list: SubtitleListUi,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
    onRoleChange: (TrackRole) -> Unit,
    onQuery: (String) -> Unit,
    onSeekToRow: (SubtitleListRow) -> Unit,
    onToggleSpoiler: () -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = true,
    registry: PlayerHitRegistry? = null,
) {
    if (!list.open) return
    val state = rememberLazyListState()
    LaunchedEffect(list.currentIndex, list.query) {
        // Auto-scroll follows playback; a search result list is left where the user put it.
        if (list.currentIndex >= 0 && list.query.isBlank()) {
            if (reduceMotion) {
                state.scrollToItem(list.currentIndex)
            } else {
                state.animateScrollToItem(list.currentIndex)
            }
        }
    }
    val panelModifier = if (landscape) {
        Modifier
            .fillMaxHeight()
            .widthIn(min = Dimens.listPanelMinWidth, max = Dimens.listPanelMaxWidth)
    } else {
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(Dimens.listPortraitShare)
    }
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = panelModifier
                .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                .blocksGestures(registry),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = Dimens.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Dimens.md)) {
                    TrackRole.entries.forEach { role ->
                        FilterChip(
                            selected = list.role == role,
                            onClick = { onRoleChange(role) },
                            label = { Text(role.title()) },
                        )
                        Spacer(Modifier.size(Dimens.xs))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onToggle) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                OutlinedTextField(
                    value = list.query,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(R.string.subtitle_list_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.md),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Dimens.md)) {
                    FilterChip(
                        selected = list.noSpoiler,
                        onClick = onToggleSpoiler,
                        label = { Text(stringResource(R.string.subtitle_list_no_spoiler)) },
                    )
                }
                if (!list.available) {
                    Text(
                        stringResource(R.string.subtitle_embedded_live),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Dimens.md),
                    )
                    return@Column
                }
                val style = listTextStyle()
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(vertical = Dimens.sm),
                ) {
                    items(list.matches, key = { it.blockId }) { row ->
                        ListRow(
                            row = row,
                            current = row.index == list.currentIndex,
                            // No-spoiler hides the lines that have not been reached yet, never the past.
                            hidden = list.noSpoiler && row.index > list.currentIndex,
                            style = style,
                            onClick = { onSeekToRow(row) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun listTextStyle(): TextStyle {
    val scale = LocalAppFontScale.current
    return remember(scale) {
        FontSpec.defaultFor(FontSurface.SUBTITLE_LIST, SubtitleLayerRole.LEARNING).toTextStyle(scale)
    }
}

@Composable
private fun ListRow(
    row: SubtitleListRow,
    current: Boolean,
    hidden: Boolean,
    style: TextStyle,
    onClick: () -> Unit,
) {
    val colors = LocalSubLearnColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (current) colors.listCurrentRow else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Dimens.touchTarget)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "${row.index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.controlIdle,
        )
        Spacer(Modifier.size(Dimens.sm))
        Text(
            text = if (hidden) "•".repeat(minOf(HIDDEN_DOTS, row.text.length)) else row.text,
            style = style,
            color = if (current) colors.controlActive else colors.controlIdle,
            maxLines = if (hidden) 1 else MAX_LIST_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(Dimens.sm))
        Text(clock(row.startMs), style = MaterialTheme.typography.labelSmall, color = colors.controlIdle)
    }
}

/** Dots shown in place of a hidden line, and the line limit for a visible one. */
private const val HIDDEN_DOTS = 3
private const val MAX_LIST_LINES = 4
