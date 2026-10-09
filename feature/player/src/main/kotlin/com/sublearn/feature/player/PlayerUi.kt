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
 * The [PlayerViewModel] is the only writer; the composables below take `PlayerUi` by value so a
 * recomposition can never observe half an update. Playback itself stays in
 * [com.sublearn.core.player.PlayerController] and is copied in here on every tick, which keeps the
 * screen testable against `FakePlayerController` without any subtitle machinery.
 *
 * Per-layer state lives in [layers] rather than in two fields, so adding a third layer (a second
 * learning language, LATER) touches no UI code.
 */
data class PlayerUi(
    val settings: AppSettings = AppSettings(),
    val playback: PlaybackState = PlaybackState(),
    /** A media or subtitle load is in flight; the screen shows a subtle progress affordance. */
    val opening: Boolean = false,
    /** Transient one-line status (seek past the end, model download progress, decoder result...). */
    val message: String? = null,
    val learningMode: LearningMode = LearningMode.ENTERTAINMENT,
    /** SUB-3: gestures go to the layers, chrome is hidden. */
    val layoutMode: Boolean = false,
    val controlsVisible: Boolean = true,
    val locked: Boolean = false,
    val sheet: Sheet = Sheet.NONE,
    /** LRN-1: the word/line/block card, or null when nothing is selected. */
    val popup: PopupUi? = null,
    /** The ML Kit translation model is missing; popups offer a download instead of an error. */
    val modelMissing: Boolean = false,
    val layers: Map<TrackRole, LayerUi> = TrackRole.entries.associateWith { LayerUi() },
    val list: SubtitleListUi = SubtitleListUi(),
    val shadowing: ShadowUi = ShadowUi(),
    val ai: AiUi = AiUi(),
    /** Subtitle text to AnnotatedString (SUB-5): marked words and above-level words get a style. */
    val wordStates: Map<String, WordVisualState> = emptyMap(),
    /** Last translation per word, so a re-tap shows the gloss instantly while the popup loads. */
    val glosses: Map<String, String> = emptyMap(),
) {
    fun layer(role: TrackRole): LayerUi = layers[role] ?: LayerUi()

    val currentBlock: SubtitleBlock? get() = layer(TrackRole.LEARNING).block ?: layer(TrackRole.TRANSLATION).block

    /** Whether the subtitle list and block stepping work, which needs a parsed file (see ARCHITECTURE). */
    val canStepBySubtitle: Boolean get() = layers.values.any { it.canStepBySubtitle }
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

data class SubtitleListUi(
    val open: Boolean = false,
    val role: TrackRole = TrackRole.LEARNING,
    val query: String = "",
    val rows: List<SubtitleListRow> = emptyList(),
    val available: Boolean = false,
    val noSpoiler: Boolean = false,
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
    val isCurrent: Boolean,
)

/**
 * Which style [com.sublearn.feature.player.styleWords] applies to a word.
 *
 * KNOWN and MARKED both come from My Words; ABOVE_LEVEL from the word level provider; PHRASE needs
 * the offline analyser and is therefore never produced yet (LATER, see EXTENSION_POINTS).
 */
enum class WordVisualState { NONE, KNOWN, MARKED, ABOVE_LEVEL, PHRASE }
