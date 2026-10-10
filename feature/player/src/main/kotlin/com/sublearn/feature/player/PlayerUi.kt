package com.sublearn.feature.player

import androidx.compose.runtime.Immutable
import com.sublearn.core.ai.AiAnswer
import com.sublearn.core.player.PlaybackState
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.TrackRole

/**
 * Everything the player screen draws, in one immutable snapshot.
 *
 * The [PlayerViewModel] is the only writer; the composables take `PlayerUi` by value so a
 * recomposition never observes a half-finished update. Playback stays in
 * [com.sublearn.core.player.PlayerController] and is copied in on every tick, which keeps the
 * screen testable against `FakePlayerController`.
 *
 * Transient gesture feedback ([hud]), the lock affordance and in-flight drags live here too, so
 * the one gesture owner and the chrome always agree on what is happening.
 */
@Immutable
data class PlayerUi(
    val settings: AppSettings = AppSettings(),
    val playback: PlaybackState = PlaybackState(),
    /** A media or subtitle load is in flight. */
    val opening: Boolean = false,
    /** Transient one-line status; cleared on its own after a few seconds. */
    val message: String? = null,
    val learningMode: LearningMode = LearningMode.ENTERTAINMENT,
    /** SUB-3: subtitle plates are draggable, gestures are off and the chrome is hidden. */
    val layoutMode: Boolean = false,
    val controlsVisible: Boolean = true,
    /** PLY-1 lock: gestures and chrome are off until the lock button is pressed again. */
    val locked: Boolean = false,
    /** While locked, the unlock button shows for a few seconds after a tap on the video. */
    val lockButtonVisible: Boolean = false,
    val sheet: Sheet = Sheet.NONE,
    /** The overflow menu is open; the chrome must not hide underneath it. */
    val menuOpen: Boolean = false,
    /** The user is dragging the seekbar; the chrome must not hide and the time label follows the thumb. */
    val scrubbing: Boolean = false,
    /** Feedback for the current gesture (brightness, volume, seek, speed, double tap). */
    val hud: GestureHud? = null,
    /** Long-press fast forward is active. */
    val holdSpeed: Boolean = false,
    /** Mute is the app's own state (not the device volume), so the chrome can show it. */
    val muted: Boolean = false,
    /** LRN-1: the word/line/block card, or null when nothing is selected. */
    val popup: PopupUi? = null,
    /** The ML Kit translation model is missing; popups offer a download instead of an error. */
    val modelMissing: Boolean = false,
    val layers: Map<TrackRole, LayerUi> = TrackRole.entries.associateWith { LayerUi() },
    /** Placements being dragged in layout mode; persisted only when the drag ends. */
    val layerDrafts: Map<TrackRole, SubtitlePlacement> = emptyMap(),
    val list: SubtitleListUi = SubtitleListUi(),
    val shadowing: ShadowUi = ShadowUi(),
    val ai: AiUi = AiUi(),
    /** SUB-5: marked words and above-level words get a style. */
    val wordStates: Map<String, WordVisualState> = emptyMap(),
    /** Last translation per word, so a re-tap shows the gloss instantly while the popup loads. */
    val glosses: Map<String, String> = emptyMap(),
) {
    fun layer(role: TrackRole): LayerUi = layers[role] ?: LayerUi()

    val currentBlock: SubtitleBlock? get() = layer(TrackRole.LEARNING).block ?: layer(TrackRole.TRANSLATION).block

    /** Whether the subtitle list and block stepping work, which needs a parsed file (see ARCHITECTURE). */
    val canStepBySubtitle: Boolean get() = layers.values.any { it.canStepBySubtitle }
}

/** One gesture's feedback. The composable only draws; the ViewModel decides the numbers. */
sealed interface GestureHud {
    /** Brightness 0..1 of the window, shown as a vertical level on the left. */
    data class Brightness(val level: Float) : GestureHud

    /** Media volume 0..1, shown as a vertical level on the right. */
    data class Volume(val level: Float) : GestureHud

    /** Horizontal seek: the absolute target and the change from where the drag started. */
    data class Seek(val targetMs: Long, val deltaMs: Long, val durationMs: Long) : GestureHud

    /** Speed in percent (100 = normal), from a two-finger drag or a long press. */
    data class Speed(val percent: Int) : GestureHud

    /** Double tap seek: how many seconds have been accumulated on this side so far. */
    data class DoubleTap(val forward: Boolean, val seconds: Int) : GestureHud
}

/** Sheets opened from the chrome or the quick actions; one enum keeps the back handling simple. */
enum class Sheet {
    NONE,
    TRACKS,
    SPEED,
    ASPECT,
    DECODER,
    LAYER_OPTIONS,
    TOOLS,
    PLAYLIST,
}

/** Where a layer's text comes from. It decides which features are possible at all. */
enum class LayerSource {
    /** Nothing attached. */
    NONE,

    /** A file we parsed ourselves: blocks, the list view, block stepping and batch tools work. */
    FILE,

    /** Live cues handed over by the player for an embedded track: no file, so no lookahead. */
    PLAYER_CUES,
}

/** What a tap asked for (SUB-4). */
enum class PopupKind { WORD, LINE, BLOCK }

/** UI state of one subtitle layer: the settings it renders with plus the current text. */
@Immutable
data class LayerUi(
    val visible: Boolean = true,
    val text: String? = null,
    val block: SubtitleBlock? = null,
    val source: LayerSource = LayerSource.NONE,
    val fileName: String? = null,
    val charsetName: String? = null,
    val warnings: List<String> = emptyList(),
    val scalePercent: Int = 100,
    val transparencyPercent: Int = 0,
    val placement: SubtitlePlacement = SubtitlePlacement(),
    val delayMs: Long = 0L,
    val embeddedTrackIndexes: List<Int> = emptyList(),
    val externalFileKeys: List<String> = emptyList(),
) {
    /** PLY-6 / SUB-6 need our own blocks, which only a parsed file gives us. */
    val canStepBySubtitle: Boolean get() = source == LayerSource.FILE
}

@Immutable
data class ShadowUi(
    val active: Boolean = false,
    val autoRepeat: Boolean = false,
    val stopAtEnd: Boolean = false,
    val repeatCount: Int = 1,
    val repeatsLeft: Int = 0,
    val pausedForRepeat: Boolean = false,
    /** "repeat 2/3" label under the controls, null when no plan is running. */
    val segmentLabel: String? = null,
)

enum class AiState { IDLE, BUSY, ANSWER, ERROR }

@Immutable
data class AiUi(
    val state: AiState = AiState.IDLE,
    val question: String? = null,
    val answer: AiAnswer? = null,
    val error: String? = null,
    /** False hides the action behind a hint that a key must be configured first (AI-1). */
    val configured: Boolean = false,
    /** AI-2: the player paused for the answer and the sheet decides when to resume. */
    val pausedForAnswer: Boolean = false,
    /** Whether playback was running when the question was asked, so the resume is exact. */
    val wasPlaying: Boolean = false,
)

/** LRN-1 card. `level` is the CEFR name as stored on a marked word, so the badge needs no lookup. */
@Immutable
data class PopupUi(
    val kind: PopupKind,
    val sourceText: String,
    val word: String? = null,
    val translation: String? = null,
    val contextTranslation: String? = null,
    val level: String? = null,
    val marked: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val timestampMs: Long = 0L,
    val needsModelDownload: Boolean = false,
    val wasPlayingBeforePause: Boolean = false,
)

@Immutable
data class SubtitleListUi(
    val open: Boolean = false,
    val role: TrackRole = TrackRole.LEARNING,
    val query: String = "",
    val rows: List<SubtitleListRow> = emptyList(),
    val available: Boolean = false,
    val noSpoiler: Boolean = false,
    /** Index of the block on screen right now; the highlight and auto-scroll follow it. */
    val currentIndex: Int = -1,
) {
    /** Rows after the search filter; a blank query shows everything. */
    val matches: List<SubtitleListRow>
        get() = if (query.isBlank()) rows else rows.filter { it.text.contains(query.trim(), ignoreCase = true) }
}

data class SubtitleListRow(
    val blockId: Long,
    val index: Int,
    val startMs: Long,
    val text: String,
)

/**
 * Which style [styleWords] applies to a word.
 *
 * KNOWN and MARKED both come from My Words; ABOVE_LEVEL from the word level provider; PHRASE needs
 * the offline analyser and is therefore never produced yet (LATER, see EXTENSION_POINTS).
 */
enum class WordVisualState { NONE, KNOWN, MARKED, ABOVE_LEVEL, PHRASE }
