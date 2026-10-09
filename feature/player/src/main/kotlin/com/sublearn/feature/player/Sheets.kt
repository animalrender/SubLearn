package com.sublearn.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.SubtitleBackdrop
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.player.PlaylistRepeat
import com.sublearn.core.player.TrackInfo
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.subtitles.TrackRole

/** Hosts every bottom sheet the player offers; one place decides which one is open. */
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
        Column(Modifier.padding(horizontal = Dimens.lg).padding(bottom = Dimens.xl)) {
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

@Composable
private fun TracksSheet(
    ui: PlayerUi,
    onSelect: (com.sublearn.core.player.TrackRef) -> Unit,
    onClear: (com.sublearn.core.player.PlayerTrackType) -> Unit,
) {
    SheetTitle(stringResource(R.string.player_subtitle_tracks))
    TrackGroup(stringResource(R.string.player_audio_tracks), ui.playback.audioTracks, onSelect, onClear)
    TrackGroup(stringResource(R.string.player_subtitle_tracks), ui.playback.textTracks, onSelect, onClear)
    if (ui.playback.audioTracks.isEmpty() && ui.playback.textTracks.isEmpty()) {
        Text(stringResource(R.string.player_no_tracks), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(Dimens.md))
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
    onSelect: (com.sublearn.core.player.TrackRef) -> Unit,
    onClear: (com.sublearn.core.player.PlayerTrackType) -> Unit,
) {
    if (tracks.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { tracks.firstOrNull()?.let { onClear(it.ref.type) } }) { Text(stringResource(R.string.player_track_auto)) }
    }
    tracks.forEach { track ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(track.ref) }
                .padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (track.isSelected) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (track.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(Dimens.sm))
            Text(
                track.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.language?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun SpeedSheet(ui: PlayerUi, onSet: (Int) -> Unit) {
    SheetTitle(stringResource(R.string.player_speed))
    val percent = ui.playback.speedPercent
    Text(stringResource(R.string.player_speed_value, percent), style = MaterialTheme.typography.headlineSmall)
    Slider(
        value = percent.toFloat(),
        onValueChange = { onSet(it.toInt()) },
        valueRange = ui.settings.player.speedMinPercent.toFloat()..ui.settings.player.speedMaxPercent.toFloat(),
        steps = speedSteps(ui.settings.player.speedMinPercent, ui.settings.player.speedMaxPercent, ui.settings.player.speedStepPercent),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        listOf(50, 75, 100, 125, 150).forEach { value ->
            FilterChip(selected = percent == value, onClick = { onSet(value) }, label = {
                Text("$value%")
            })
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
        Row(
            Modifier.fillMaxWidth().clickable { onSet(mode) }.padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (ui.playback.aspect == mode) Icons.Default.Check else Icons.Default.Close, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Dimens.sm))
            Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
    }
    var width by remember { mutableStateOf(ui.settings.player.customAspectWidth.toFloat()) }
    var height by remember { mutableStateOf(ui.settings.player.customAspectHeight.toFloat()) }
    Text(stringResource(R.string.player_aspect_custom), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        Slider(value = width, onValueChange = { width = it }, valueRange = 4f..32f, modifier = Modifier.weight(1f))
        Slider(value = height, onValueChange = { height = it }, valueRange = 4f..32f, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCustom(width.toInt(), height.toInt()) }) { Text(stringResource(R.string.action_apply)) }
    }
    Text(
        text = "%.2f : 1".format(width / height),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
        Row(
            Modifier.fillMaxWidth().clickable { onSet(mode) }.padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (ui.playback.decoder == mode) Icons.Default.Check else Icons.Default.Close, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Dimens.sm))
            Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
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

/** Per-layer controls for delay, size, transparency and the file behind the layer (SUB-3). */
@Composable
private fun LayerOptionsSheet(ui: PlayerUi, viewModel: PlayerViewModel) {
    var role by remember { mutableStateOf(TrackRole.LEARNING) }
    SheetTitle(stringResource(R.string.settings_category_subtitles))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TrackRole.entries.forEach { entry ->
            FilterChip(selected = role == entry, onClick = { role = entry }, label = {
                Text(entry.title())
            })
        }
    }
    val layer = ui.layer(role)
    val settings = ui.settings.subtitleLayer(role)
    Spacer(Modifier.height(Dimens.md))
    Text(stringResource(R.string.subtitle_delay), style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TextButton(onClick = { viewModel.setLayerDelay(role, -100L) }) { Text("-100") }
        Text(stringResource(R.string.subtitle_delay_value, settings.delayMs), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { viewModel.setLayerDelay(role, 100L) }) { Text("+100") }
        TextButton(onClick = { viewModel.updateLayer(role) { it.copy(delayMs = 0L) } }) { Text(stringResource(R.string.action_reset)) }
    }
    Text(stringResource(R.string.subtitle_size), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = settings.scalePercent.toFloat(),
        onValueChange = { viewModel.setLayerScale(role, it.toInt()) },
        valueRange = 60f..220f,
    )
    Text(stringResource(R.string.subtitle_transparency), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = settings.transparencyPercent.toFloat(),
        onValueChange = { viewModel.setLayerTransparency(role, it.toInt()) },
        valueRange = 0f..100f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        FilterChip(
            selected = settings.visible,
            onClick = { viewModel.toggleLayerVisible(role) },
            label = { Text(stringResource(R.string.action_apply)) },
        )
        FilterChip(
            selected = settings.showInList,
            onClick = { viewModel.toggleListFor(role) },
            label = { Text(stringResource(R.string.subtitle_list)) },
        )
    }
    Spacer(Modifier.height(Dimens.md))
    Text(
        text = layer.fileName?.let { "$it · ${layer.charsetName ?: ""}" } ?: stringResource(R.string.subtitle_list_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TextButton(onClick = { viewModel.setLayoutMode(true) }) {
            Text(stringResource(R.string.subtitle_layout_mode))
        }
        layer.fileName?.let { name ->
            TextButton(onClick = { viewModel.removeFile(role, ui.settings.subtitleLayer(role).externalFileKeys.firstOrNull() ?: name) }) {
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
    var removeBreaks by remember { mutableStateOf(ui.settings.subtitles.normalizer.removeLineBreaks) }
    var maxChars by remember { mutableStateOf(ui.settings.subtitles.normalizer.maxBlockChars.toFloat()) }
    var mergeGap by remember { mutableStateOf(ui.settings.subtitles.normalizer.mergeGapMs.toFloat()) }
    val appliedMessage = stringResource(R.string.subtitle_tool_apply)
    SheetTitle(stringResource(R.string.subtitle_tools))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = removeBreaks, onClick = { removeBreaks = !removeBreaks }, label = {
            Text(stringResource(R.string.subtitle_tool_line_breaks), style = MaterialTheme.typography.labelMedium)
        })
    }
    Text(stringResource(R.string.subtitle_tool_max_chars), style = MaterialTheme.typography.labelLarge)
    Slider(value = maxChars, onValueChange = { maxChars = it }, valueRange = 40f..160f)
    Text("${maxChars.toInt()}", style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.subtitle_delay), style = MaterialTheme.typography.labelLarge)
    Slider(value = mergeGap, onValueChange = { mergeGap = it }, valueRange = 0f..2000f)
    Text("${mergeGap.toInt()} ms", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(Dimens.md))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        TextButton(onClick = {
            viewModel.updateSettings { settings ->
                settings.copy(
                    subtitles = settings.subtitles.copy(
                        normalizer = settings.subtitles.normalizer.copy(
                            removeLineBreaks = removeBreaks,
                            maxBlockChars = maxChars.toInt(),
                            mergeGapMs = mergeGap.toLong(),
                        ),
                    ),
                )
            }
            ui.playback.target?.let { target ->
                TrackRole.entries.forEach { role ->
                    ui.settings.subtitleLayer(role).externalFileKeys.forEach { key ->
                        viewModel.loadFile(role, key, key.substringAfterLast('/'))
                    }
                }
                viewModel.message(appliedMessage)
            }
        }) { Text(stringResource(R.string.subtitle_tool_apply)) }
    }
}

@Composable
private fun PlaylistSheet(ui: PlayerUi, viewModel: PlayerViewModel) {
    SheetTitle(stringResource(R.string.player_playlist))
    ui.playback.playlist.forEachIndexed { index, target ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = Dimens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 24.dp))
            Spacer(Modifier.size(Dimens.sm))
            Text(target.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
        PlaylistRepeat.entries.forEach { repeat ->
            FilterChip(
                selected = ui.playback.repeat == repeat,
                onClick = { viewModel.setRepeat(repeat) },
                label = { Text(repeat.name.lowercase()) },
            )
        }
    }
}

/**
 * The subtitle list (SUB-8): search, jump, no-spoiler. Only file-backed layers can offer it, and an
 * embedded track says so instead of showing an empty panel.
 */
@Composable
fun SubtitleListPanel(
    list: SubtitleListUi,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
    onRoleChange: (TrackRole) -> Unit,
    onQuery: (String) -> Unit,
    onSeekToRow: (SubtitleListRow) -> Unit,
    onToggleSpoiler: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!list.open) return
    val state = rememberLazyListState()
    LaunchedEffect(list.currentIndex, list.query) {
        if (list.currentIndex >= 0 && list.query.isBlank()) {
            runCatching { state.animateScrollToItem(list.currentIndex.coerceAtLeast(0)) }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(min = Dimens.listPanelMinWidth, max = 420.dp),
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
                    IconButton(onClick = onToggle) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
                }
                OutlinedTextField(
                    value = list.query,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(R.string.subtitle_list_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.md),
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
                LazyColumn(state = state, contentPadding = PaddingValues(vertical = Dimens.sm)) {
                    items(list.matches, key = { it.blockId }) { row ->
                        ListRow(
                            row = row,
                            noSpoiler = list.noSpoiler && !row.isCurrent,
                            style = uiListStyle(),
                            onClick = { onSeekToRow(row) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun uiListStyle(): androidx.compose.ui.text.TextStyle {
    val scale = LocalAppFontScale.current
    return remember(scale) {
        com.sublearn.core.settings.FontSpec
            .defaultFor(FontSurface.SUBTITLE_LIST, SubtitleLayerRole.LEARNING)
            .toTextStyle(scale)
    }
}

@Composable
private fun ListRow(row: SubtitleListRow, noSpoiler: Boolean, style: androidx.compose.ui.text.TextStyle, onClick: () -> Unit) {
    val background = if (row.isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    SubtitleBackdrop(
        alpha = if (row.isCurrent) 1f else 0f,
        colorArgb = null,
        cornerRadiusDp = 8f,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.md, vertical = Dimens.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text("${row.index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.size(Dimens.sm))
            Text(
                text = if (noSpoiler) "•".repeat(minOf(3, row.text.length)) else row.text,
                style = style,
                maxLines = if (noSpoiler) 1 else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(Dimens.sm))
            Text(clock(row.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
