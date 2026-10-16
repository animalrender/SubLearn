package com.sublearn.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDirection as ComposeTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sublearn.core.designsystem.Dimens
import com.sublearn.core.designsystem.LocalAppFontScale
import com.sublearn.core.designsystem.LocalReduceMotion
import com.sublearn.core.designsystem.LocalSubLearnColors
import com.sublearn.core.designsystem.Motion
import com.sublearn.core.designsystem.toTextStyle
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.SubtitleVerticalAnchor
import com.sublearn.core.subtitles.TrackRole

/** Passive, bounded hints: no click target, no focus, and no control or subtitle panel underneath. */
@Composable
internal fun LearningCardsOverlay(ui: PlayerUi, inPip: Boolean, modifier: Modifier = Modifier) {
    val learning = ui.settings.learning
    val show = learning.mode == LearningMode.LEARNING && ui.playback.isPlaying && !inPip &&
        !ui.controlsVisible && !ui.locked && !ui.layoutMode && !ui.scrubbing && !ui.list.open &&
        ui.sheet == Sheet.NONE && !ui.menuOpen && ui.popup == null && ui.ai.state == AiState.IDLE &&
        ui.layer(TrackRole.LEARNING).visible
    val cards = if (show) ui.learningCards.take(VISIBLE_LEARNING_CARDS) else emptyList()
    val placement = learning.popupCorner
    val top = placement.vertical == SubtitleVerticalAnchor.TOP
    val start = placement.horizontal != SubtitleHorizontalAnchor.END
    val anchor = when {
        top && start -> Alignment.TopStart
        top -> Alignment.TopEnd
        start -> Alignment.BottomStart
        else -> Alignment.BottomEnd
    }
    val colors = LocalSubLearnColors.current
    val scale = LocalAppFontScale.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val risePx = with(density) { learning.popupRiseDp.coerceIn(0f, 80f).dp.roundToPx() }
    val wordStyle = ui.settings.fontFor(FontSurface.LEARNING_POPUP).toTextStyle(scale)
        .copy(textDirection = ComposeTextDirection.ContentOrLtr)
    val nativeStyle = ui.settings.fontFor(FontSurface.LEARNING_POPUP, SubtitleLayerRole.NATIVE).toTextStyle(scale)
        .copy(textDirection = ComposeTextDirection.ContentOrRtl)

    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Dimens.lg)) {
        val cardWidth = minOf(Dimens.learningCardWidth, maxWidth * Dimens.learningCardWidthShare)
        // Legacy/imported offsets are bounded to this viewport; cards cannot drift outside a short landscape video.
        val x = placement.offsetXDp.coerceAtLeast(0f).dp.coerceAtMost(maxWidth / 4)
        val y = placement.offsetYDp.coerceAtLeast(0f).dp.coerceAtMost(maxHeight / 4)
        val cornerPadding = if (start) {
            Modifier.padding(start = x, top = if (top) y else Dimens.xxs, bottom = if (top) Dimens.xxs else y)
        } else {
            Modifier.padding(end = x, top = if (top) y else Dimens.xxs, bottom = if (top) Dimens.xxs else y)
        }
        AnimatedContent(
            targetState = cards,
            modifier = Modifier.align(anchor).then(cornerPadding).width(cardWidth)
                .heightIn(max = maxHeight * Dimens.learningCardHeightShare).clipToBounds(),
            contentAlignment = anchor,
            transitionSpec = {
                (Motion.popupEnter(reduceMotion, risePx, fadeInMs = learning.popupFadeInMs.toInt()) togetherWith
                    Motion.popupExit(reduceMotion, fadeOutMs = learning.popupFadeOutMs.toInt()))
                    .using(SizeTransform(clip = false))
            },
            label = "learning hints",
        ) { visibleCards ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                visibleCards.forEach { card ->
                    Surface(
                        shape = RoundedCornerShape(Dimens.radiusMd),
                        color = colors.subtitleBackdrop.copy(alpha = learning.popupOpacityPercent.coerceIn(30, 100) / 100f),
                        contentColor = colors.subtitleText,
                        shadowElevation = Dimens.cardElevation,
                        border = BorderStroke(Dimens.xxs, colors.overlayOutline),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(Dimens.sm), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                                Text(card.word, style = wordStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                card.level?.let { LevelBadge(it) }
                            }
                            Text(card.translation, style = nativeStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
