package com.sublearn.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.R
import com.sublearn.core.designsystem.SubtitleBackdrop
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.QuickActionSpec
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.subtitles.TrackRole

/** Fade/slide for the chrome; the timing comes from the design system, never from a literal here. */
@Composable
fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = Motion.controlsEnter(reduce),
        exit = Motion.controlsExit(reduce),
        modifier = modifier,
    ) { content() }
}

@Composable
fun TopBar(
    ui: PlayerUi,
    onBack: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenSheet: (Sheet) -> Unit,
    onPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .background(LocalSubLearnColors.current.playerScrim)
                .padding(horizontal = Dimens.sm, vertical = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeIcon(Icons.Default.Close, stringResource(R.string.action_close), onClick = onBack)
            Spacer(Modifier.size(Dimens.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.playback.target?.displayName ?: stringResource(R.string.player_no_media),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ui.layers.values.count { it.source == LayerSource.FILE }.toString() + " / " +
                        TrackRole.entries.size + " " + stringResource(R.string.settings_category_subtitles),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            ChromeIcon(Icons.Default.Translate, stringResource(R.string.subtitle_tools), onClick = { onOpenSheet(Sheet.TOOLS) })
            ChromeIcon(Icons.Default.List, stringResource(R.string.player_audio_tracks), onClick = { onOpenSheet(Sheet.TRACKS) })
            ChromeIcon(Icons.Default.Speed, stringResource(R.string.player_speed), onClick = { onOpenSheet(Sheet.SPEED) })
            ChromeIcon(Icons.Default.Fullscreen, stringResource(R.string.player_pip), onClick = onPip)
            ChromeIcon(Icons.Default.Lock, stringResource(R.string.player_lock), onClick = onToggleLock)
            ChromeIcon(Icons.Default.Settings, stringResource(R.string.menu_settings), onClick = { onOpenSheet(Sheet.DECODER) })
        }
    }
}

@Composable
fun BottomControls(
    ui: PlayerUi,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStepBlock: (Int) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleStop: () -> Unit,
    onToggleList: () -> Unit,
    onAskAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = ui.playback
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalSubLearnColors.current.playerScrim)
            .padding(horizontal = Dimens.md, vertical = Dimens.xs),
        verticalArrangement = Arrangement.spacedBy(Dimens.xs),
    ) {
        SeekRow(
            progress = state.progressFraction,
            buffered = state.bufferedFraction,
            positionLabel = clock(state.positionMs),
            durationLabel = if (state.isLive) "LIVE" else clock(state.durationMs),
            onSeekFraction = { fraction -> onSeek((fraction * state.durationMs).toLong() - state.positionMs) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            val step = ui.settings.player.seekStepMs
            ChromeIcon(Icons.Default.SkipPrevious, stringResource(R.string.player_previous_subtitle)) { onStepBlock(-1) }
            ChromeIcon(Icons.Default.FastRewind, stringResource(R.string.player_seek_backward)) { onSeek(-step) }
            Box(Modifier.size(Dimens.iconButtonLarge), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp)) {}
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(Dimens.iconButtonLarge)) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) {
                            stringResource(R.string.settings_double_tap_pause)
                        } else {
                            stringResource(R.string.action_apply)
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            ChromeIcon(Icons.Default.FastForward, stringResource(R.string.player_seek_forward)) { onSeek(step) }
            ChromeIcon(Icons.Default.SkipNext, stringResource(R.string.player_next_subtitle)) { onStepBlock(1) }
            Spacer(Modifier.weight(1f))
            ChromeIcon(
                imageVector = if (ui.shadowing.active) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = stringResource(R.string.player_repeat_subtitle),
                onClick = onToggleRepeat,
                selected = ui.shadowing.active,
            )
            ChromeIcon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = stringResource(R.string.player_stop_at_end),
                onClick = onToggleStop,
                selected = ui.shadowing.stopAtEnd,
            )
            ChromeIcon(Icons.Default.Subtitles, stringResource(R.string.subtitle_list), onClick = onToggleList, selected = ui.list.open)
            ChromeIcon(
                Icons.Default.AutoAwesome,
                stringResource(R.string.ai_explain),
                onClick = onAskAi,
                selected = ui.ai.state == AiState.BUSY,
            )
            Text(
                text = "%d%%".format(state.speedPercent),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SeekRow(progress: Float, buffered: Float, positionLabel: String, durationLabel: String, onSeekFraction: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(positionLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        Box(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(
                progress = { buffered },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = Color.White.copy(alpha = 0.35f),
                trackColor = Color.White.copy(alpha = 0.12f),
            )
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = onSeekFraction,
                modifier = Modifier.fillMaxWidth(),
                thumb = { Box(Modifier.size(11.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) },
                track = { },
            )
        }
        Text(durationLabel, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
    }
}

/** The dock and the floating buttons, from the same settings model (SUB-7, QCK). */
@Composable
fun QuickActionBar(
    ui: PlayerUi,
    onAction: (QuickActionId, Boolean) -> Unit,
    onAddSubtitleFile: () -> Unit,
    onMoveFloating: (QuickActionId, Float, Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val bar = ui.settings.quickActions.visibleIn(DockMode.BAR)
    val floating = ui.settings.quickActions.visibleIn(DockMode.FLOATING)
    Column(modifier = modifier.fillMaxSize()) {
        if (bar.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Dimens.md),
                horizontalArrangement = Arrangement.spacedBy(Dimens.xs),
            ) {
                bar.forEach { (id, spec) ->
                    QuickActionIcon(
                        id = id,
                        ui = ui,
                        spec = spec,
                        inverted = ui.settings.shadowing.holdInvertsTemporarily,
                        onClick = { onAction(id, false) },
                        onLongClick = { onAction(id, true) },
                        onAddSubtitleFile = onAddSubtitleFile,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
    floating.forEach { (id, spec) ->
        Box(Modifier.fillMaxSize()) {
            QuickActionIcon(
                id = id,
                ui = ui,
                spec = spec,
                inverted = ui.settings.shadowing.holdInvertsTemporarily,
                onClick = { onAction(id, false) },
                onLongClick = { onAction(id, true) },
                onAddSubtitleFile = onAddSubtitleFile,
                dragModifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = (spec.xFraction * 1000f).dp, top = (spec.yFraction * 1000f).dp),
                onDragged = { dx, dy ->
                    onMoveFloating(
                        id,
                        (spec.xFraction + dx / 1000f).coerceIn(0f, 1f),
                        (spec.yFraction + dy / 1000f).coerceIn(0f, 1f),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickActionIcon(
    id: QuickActionId,
    ui: PlayerUi,
    spec: QuickActionSpec,
    inverted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAddSubtitleFile: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    onDragged: (Float, Float) -> Unit = { _, _ -> },
) {
    val selected = when (id) {
        QuickActionId.TOGGLE_LEARNING -> ui.settings.subtitleLayer(TrackRole.LEARNING).visible
        QuickActionId.TOGGLE_TRANSLATION -> ui.settings.subtitleLayer(TrackRole.TRANSLATION).visible
        QuickActionId.REPEAT_BLOCK -> ui.shadowing.active
        QuickActionId.STOP_AT_BLOCK_END -> ui.shadowing.stopAtEnd
        QuickActionId.LAYOUT_MODE -> ui.layoutMode
        QuickActionId.SUBTITLE_LIST -> ui.list.open
        else -> false
    }
    val label = stringResource(id.labelRes())
    SubtitleBackdrop(
        alpha = (1f - spec.transparencyPercent / 100f).coerceIn(0.15f, 1f),
        colorArgb = null,
        cornerRadiusDp = 12f,
        contentPaddingHorizontal = 2,
        contentPaddingVertical = 2,
        modifier = modifier
            .then(dragModifier)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = if (inverted) onLongClick else null),
    ) {
        Icon(
            imageVector = id.icon(),
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(spec.sizeDp.dp),
        )
        if (selected && id == QuickActionId.REPEAT_BLOCK && ui.shadowing.repeatsLeft > 0) {
            Text(
                text = ui.shadowing.repeatsLeft.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One chrome button: white on the scrim, accent when the state it toggles is on. */
@Composable
internal fun ChromeIcon(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(Dimens.iconButton)) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

@Composable
fun Snackbarish(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.inverseSurface) {
        Row(Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 2)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun LayoutModeBanner(hint: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(Dimens.md),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = Dimens.md, vertical = Dimens.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.size(Dimens.sm))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_save)) }
        }
    }
}

internal fun QuickActionId.icon(): ImageVector = when (this) {
    QuickActionId.TOGGLE_LEARNING -> Icons.Default.Subtitles
    QuickActionId.TOGGLE_TRANSLATION -> Icons.Default.Translate
    QuickActionId.REPEAT_BLOCK -> Icons.Default.Repeat
    QuickActionId.STOP_AT_BLOCK_END -> Icons.Default.StopCircle
    QuickActionId.LAYOUT_MODE -> Icons.Default.DragIndicator
    QuickActionId.SUBTITLE_LIST -> Icons.Default.List
    QuickActionId.AI_EXPLAIN -> Icons.Default.AutoAwesome
    QuickActionId.MY_WORDS -> Icons.Default.Star
    QuickActionId.PLAYLIST -> Icons.Default.PlaylistPlay
    QuickActionId.ASPECT_RATIO -> Icons.Default.Fullscreen
    QuickActionId.DECODER -> Icons.Default.Memory
    QuickActionId.AUDIO_TRACK -> Icons.Default.GraphicEq
    QuickActionId.SUBTITLE_TRACKS -> Icons.Default.Subtitles
    QuickActionId.SUBTITLE_TOOLS -> Icons.Default.Tune
    QuickActionId.SPEED -> Icons.Default.Speed
    QuickActionId.PICTURE_IN_PICTURE -> Icons.Default.PictureInPictureAlt
    QuickActionId.SHARE -> Icons.Default.Share
    QuickActionId.SETTINGS -> Icons.Default.Settings
}

internal fun QuickActionId.labelRes(): Int = when (this) {
    QuickActionId.TOGGLE_LEARNING -> R.string.subtitle_layer_learning
    QuickActionId.TOGGLE_TRANSLATION -> R.string.subtitle_layer_translation
    QuickActionId.REPEAT_BLOCK -> R.string.player_repeat_subtitle
    QuickActionId.STOP_AT_BLOCK_END -> R.string.player_stop_at_end
    QuickActionId.LAYOUT_MODE -> R.string.subtitle_layout_mode
    QuickActionId.SUBTITLE_LIST -> R.string.subtitle_list
    QuickActionId.AI_EXPLAIN -> R.string.ai_explain
    QuickActionId.MY_WORDS -> R.string.words_title
    QuickActionId.PLAYLIST -> R.string.player_playlist
    QuickActionId.ASPECT_RATIO -> R.string.player_aspect
    QuickActionId.DECODER -> R.string.player_decoder
    QuickActionId.AUDIO_TRACK -> R.string.player_audio_tracks
    QuickActionId.SUBTITLE_TRACKS -> R.string.player_subtitle_tracks
    QuickActionId.SUBTITLE_TOOLS -> R.string.subtitle_tools
    QuickActionId.SPEED -> R.string.player_speed
    QuickActionId.PICTURE_IN_PICTURE -> R.string.player_pip
    QuickActionId.SHARE -> R.string.action_save
    QuickActionId.SETTINGS -> R.string.menu_settings
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
