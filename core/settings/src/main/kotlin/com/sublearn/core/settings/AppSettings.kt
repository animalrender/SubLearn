package com.sublearn.core.settings

import com.sublearn.core.subtitles.NormalizerConfig
import com.sublearn.core.subtitles.TrackRole
import kotlinx.serialization.Serializable

/** Global theme selection. AMOLED keeps a pure black background so OLED pixels switch off. */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@Serializable
enum class AccentToken { TEAL, INDIGO, AMBER, ROSE, FOREST, VIOLET, SKY }

@Serializable
enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE, REVERSE_LANDSCAPE, SENSOR }

@Serializable
enum class DecoderMode {
    /** Media3/ExoPlayer software decoders (MP4/WebM/MKV in software). */
    SOFTWARE,

    /** Android MediaCodec, adaptive (the MX "HW" behaviour). */
    HARDWARE,

    /** MediaCodec with low-latency/professional profiles, falling back to [HARDWARE]. */
    HARDWARE_PLUS,
    ;

    companion object {
        fun fromKey(key: String): DecoderMode = entries.firstOrNull { it.name == key } ?: SOFTWARE
    }
}

@Serializable
enum class AspectMode { FIT, STRETCH, ZOOM, FILL, CUSTOM_RATIO }

@Serializable
enum class DoubleTapAction { PAUSE, SEEK, NONE }

@Serializable
enum class TapCountAction { TRANSLATE_WORD, TRANSLATE_LINE, TRANSLATE_BLOCK, NOTHING }

@Serializable
enum class LearningMode { ENTERTAINMENT, LEARNING, OFF }

@Serializable
enum class CefrLevel { A1, A2, B1, B2, C1, C2, UNKNOWN }

@Serializable
enum class DictionaryPreference { OFFLINE_FIRST, WEB_TRANSLATE_ONLY, OFFLINE_ONLY }

@Serializable
enum class AiProviderToken { GEMINI, OPENAI, ANTHROPIC, CUSTOM }

/** Which subtitle layer a control belongs to; both layers are independent (PLY-7). */
@Serializable
enum class LayerRole { LEARNING, TRANSLATION }

@Serializable
enum class SubtitleVerticalAnchor { TOP, CENTER, BOTTOM }

@Serializable
enum class SubtitleHorizontalAnchor { START, CENTER, END }

/** Alignment of a subtitle layer; expressed as anchors plus pixel offsets so it survives rotation. */
@Serializable
data class SubtitlePlacement(
    val vertical: SubtitleVerticalAnchor = SubtitleVerticalAnchor.BOTTOM,
    val horizontal: SubtitleHorizontalAnchor = SubtitleHorizontalAnchor.CENTER,
    val offsetXDp: Float = 0f,
    val offsetXDpFraction: Float = 0f,
    val offsetYDp: Float = 0f,
)

/** Per-layer options, one tab per layer in the subtitle dialog (PLY-7). */
@Serializable
data class SubtitleLayerSettings(
    val visible: Boolean = true,
    val delayMs: Long = 0L,
    val scalePercent: Int = 100,
    val transparencyPercent: Int = 0,
    val placement: SubtitlePlacement = SubtitlePlacement(),
    /** Media3 track indexes shown in this layer; empty means "auto-pick by language". */
    val embeddedTrackIndexes: List<Int> = emptyList(),
    /** External subtitle file keys (SubtitleFile.key) attached to this layer. */
    val externalFileKeys: List<String> = emptyList(),
    /** Order in which the layer's tracks are stacked when several are enabled. */
    val stackDirection: LayerStackDirection = LayerStackDirection.BELOW,
    val showInList: Boolean = true,
    /** Keep the raw `\N` line breaks of ASS/SRT files instead of joining them. */
    val keepOriginalLineBreaks: Boolean = false,
)

@Serializable
enum class LayerStackDirection { ABOVE, BELOW }

@Serializable
data class SubtitleSettings(
    val layers: Map<LayerRole, SubtitleLayerSettings> = defaultLayers(),
    val normalizer: NormalizerConfig = NormalizerConfig.DEFAULT,
    /** PLY-6: hide upcoming blocks so the plot is not spoiled. */
    val noSpoilerMode: Boolean = false,
    /** Long-press on the list toggle flips this, per PLY-6. */
    val listPanelEnabled: Boolean = false,
    val listPanelWidthPercent: Int = 32,
    val listAutoScroll: Boolean = true,
    val highlightCurrentBlock: Boolean = true,
    val autoLoadExternal: Boolean = true,
    /** Optional tree URI the user granted for sidecar lookup; null means "pick files by hand". */
    val sidecarTreeUri: String? = null,
    val showLanguageBadge: Boolean = false,
    val maxLinesPerLayer: Int = 3,
) {
    fun layer(role: TrackRole): SubtitleLayerSettings =
        layers[if (role == TrackRole.LEARNING) LayerRole.LEARNING else LayerRole.TRANSLATION] ?: SubtitleLayerSettings()

    fun updated(role: TrackRole, value: SubtitleLayerSettings): SubtitleSettings =
        copy(layers = layers + ((if (role == TrackRole.LEARNING) LayerRole.LEARNING else LayerRole.TRANSLATION) to value))

    companion object {
        fun defaultLayers(): Map<LayerRole, SubtitleLayerSettings> = mapOf(
            LayerRole.LEARNING to SubtitleLayerSettings(
                visible = true,
                placement = SubtitlePlacement(SubtitleVerticalAnchor.BOTTOM, SubtitleHorizontalAnchor.CENTER, offsetYDp = 96f),
            ),
            LayerRole.TRANSLATION to SubtitleLayerSettings(
                visible = true,
                transparencyPercent = 8,
                placement = SubtitlePlacement(SubtitleVerticalAnchor.BOTTOM, SubtitleHorizontalAnchor.CENTER, offsetYDp = 40f),
            ),
        )
    }
}

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentToken = AccentToken.TEAL,
    val useDynamicColor: Boolean = false,
    val appFontScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val cornerRadiusDp: Float = 18f,
    val showRipples: Boolean = true,
)

@Serializable
data class PlayerSettings(
    val controlsAutoHideMs: Long = 3_000L,
    val showControlsWhilePaused: Boolean = true,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.PAUSE,
    val doubleTapSeekMs: Long = 30_000L,
    val seekStepMs: Long = 10_000L,
    val swipeSeekSecondsPerScreen: Int = 90,
    val defaultSpeedPercent: Int = 100,
    val speedStepPercent: Int = 25,
    val speedMinPercent: Int = 25,
    val speedMaxPercent: Int = 400,
    val twoFingerSpeedShortcut: Boolean = true,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    val keepScreenOn: Boolean = true,
    val rememberPosition: Boolean = true,
    val resumeOnOpen: Boolean = true,
    val autoPlayNextInPlaylist: Boolean = true,
    val decoder: DecoderMode = DecoderMode.HARDWARE,
    val aspect: AspectMode = AspectMode.FIT,
    val customAspectWidth: Int = 16,
    val customAspectHeight: Int = 9,
    val enterPipOnLeave: Boolean = false,
    val showBufferIndicator: Boolean = true,
    val showSpeedBadge: Boolean = true,
    val dimOverlayPercent: Int = 0,
    val brightnessFollowsSystem: Boolean = true,
    val volumeUsesDeviceStream: Boolean = true,
    val subtitleSafeMarginDp: Float = 0f,
    val lockGesturesWhenLocked: Boolean = true,
)

@Serializable
data class GestureSettings(
    val mapping: Map<String, String> = emptyMap(),
    val invertVertical: Boolean = false,
    val horizontalEnabled: Boolean = true,
    val systemGestureSafeDp: Float = 24f,
    val edgeExclusionDp: Float = 20f,
) {
    fun actionFor(slot: GestureSlot): GestureAction =
        mapping[slot.key]?.let { GestureAction.fromKey(it) } ?: slot.defaultAction

    fun withAction(slot: GestureSlot, action: GestureAction): GestureSettings =
        copy(mapping = if (action == slot.defaultAction) mapping - slot.key else mapping + (slot.key to action.name))

    fun reset(slot: GestureSlot): GestureSettings = copy(mapping = mapping - slot.key)
}

/**
 * Gesture slots with a stable persisted key. Priority between them is fixed by
 * `GestureDispatcher` (subtitle text > buttons > surface), never by the mapping, so remapping
 * cannot create a conflict with the subtitle tap-to-translate contract.
 */
@Serializable
enum class GestureSlot(val key: String, val defaultAction: GestureAction) {
    LEFT_VERTICAL("left_vertical", GestureAction.BRIGHTNESS),
    RIGHT_VERTICAL("right_vertical", GestureAction.VOLUME),
    HORIZONTAL("horizontal", GestureAction.SEEK),
    DOUBLE_TAP_CENTER("double_tap_center", GestureAction.TOGGLE_PLAY),
    DOUBLE_TAP_LEFT("double_tap_left", GestureAction.SEEK_BACKWARD),
    DOUBLE_TAP_RIGHT("double_tap_right", GestureAction.SEEK_FORWARD),
    TWO_FINGER_VERTICAL("two_finger_vertical", GestureAction.PLAYBACK_SPEED),
    PINCH("pinch", GestureAction.ASPECT_RATIO),
    ;

    companion object {
        fun fromKey(key: String): GestureSlot? = entries.firstOrNull { it.key == key }
    }
}

@Serializable
enum class GestureAction {
    NONE,
    BRIGHTNESS,
    VOLUME,
    SEEK,
    SEEK_FORWARD,
    SEEK_BACKWARD,
    TOGGLE_PLAY,
    TOGGLE_CONTROLS,
    PLAYBACK_SPEED,
    ASPECT_RATIO,
    REPEAT_BLOCK,
    TOGGLE_LEARNING_SUBTITLE,
    TOGGLE_TRANSLATION_SUBTITLE,
    LOCK,
    NEXT_BLOCK,
    PREVIOUS_BLOCK,
    TOGGLE_MUTE,
    ;

    companion object {
        fun fromKey(key: String): GestureAction? = entries.firstOrNull { it.name == key }
    }
}

/**
 * Shadowing configuration (SHD-1..SHD-3). The pause formula is intentionally parametric because a
 * fixed pause feels wrong for both a 1 s line and an 8 s paragraph.
 */
@Serializable
data class ShadowingSettings(
    val repeatCount: Int = 1,
    val pauseBaseMs: Long = 400L,
    /** Pause = base + multiplier x block duration, clamped to [pauseMaxMs]. */
    val pauseDurationMultiplier: Float = 0.35f,
    val pauseMaxMs: Long = 4_000L,
    /** Extra lead-in before a repeat so the mouth has time to settle. */
    val preRollMs: Long = 250L,
    val autoRepeatEnabled: Boolean = false,
    val stopAtEndOfBlock: Boolean = false,
    /** Hold-to-invert works for both repeat and stop toggles (SHD-3). */
    val holdInvertsTemporarily: Boolean = true,
    val showRepeatRing: Boolean = true,
    val repeatOnEveryBlock: Boolean = false,
    val minBlockDurationMs: Long = 300L,
)

@Serializable
data class LearningSettings(
    val mode: LearningMode = LearningMode.ENTERTAINMENT,
    /** Manual level used until automatic detection (LATER) exists. */
    val manualLevel: CefrLevel = CefrLevel.B1,
    val showAboveLevelOnly: Boolean = true,
    val maxPopupCards: Int = 4,
    val popupLifetimeMs: Long = 4_500L,
    val popupFadeInMs: Long = 260L,
    val popupFadeOutMs: Long = 520L,
    val popupRiseDp: Float = 26f,
    val popupCorner: SubtitlePlacement = SubtitlePlacement(
        vertical = SubtitleVerticalAnchor.BOTTOM,
        horizontal = SubtitleHorizontalAnchor.START,
        offsetXDp = 18f,
        offsetYDp = 210f,
    ),
    val popupOpacityPercent: Int = 82,
    val showPhraseTranslation: Boolean = true,
    val multiWordSelection: Boolean = true,
    val tapCountsForLineAndBlock: Boolean = true,
    val multiTapWindowMs: Long = 320L,
    val bookmarkButton: Boolean = true,
    val openDetailsWith: DetailsOpenTrigger = DetailsOpenTrigger.ICON,
    val dictionaryPreference: DictionaryPreference = DictionaryPreference.WEB_TRANSLATE_ONLY,
    /** When a word is marked in My Words it counts as "known" for the popup filter. */
    val markedWordsAreKnown: Boolean = true,
)

@Serializable
enum class DetailsOpenTrigger { ICON, LONG_PRESS, DOUBLE_TAP }

/** Word colouring (SUB-5). Styles are NOW, the detectors that feed them are LATER. */
@Serializable
data class WordStyleSettings(
    val byPartOfSpeech: Map<String, WordStyle> = emptyMap(),
    val myWords: WordStyle = WordStyle(colorArgb = 0xFF7BE3A1, decoration = TextDecorationToken.UNDERLINE, bold = true),
    val phrases: WordStyle = WordStyle(colorArgb = 0xFF8FC8FF, decoration = TextDecorationToken.DOTTED_UNDERLINE),
    val unknownAboveLevel: WordStyle = WordStyle(colorArgb = 0xFFFFD98A, decoration = TextDecorationToken.NONE, bold = true),
    val enabled: Boolean = true,
)

@Serializable
data class WordStyle(
    val colorArgb: Long? = null,
    val backgroundArgb: Long? = null,
    val decoration: TextDecorationToken = TextDecorationToken.NONE,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val scalePercent: Int = 100,
    val alphaPercent: Int = 100,
) {
    companion object {
        val NONE = WordStyle()
    }
}

/** AI helper (AI-1..AI-4). The key itself is never stored here; see `AiSecretStore`. */
@Serializable
data class AiSettings(
    val enabled: Boolean = true,
    val provider: AiProviderToken = AiProviderToken.GEMINI,
    val model: String = "gemini-2.0-flash",
    val customBaseUrl: String = "",
    val temperaturePercent: Int = 30,
    val maxOutputTokens: Int = 700,
    val timeoutMs: Long = 30_000L,
    val contextBlocks: Int = 10,
    val includeTitle: Boolean = true,
    val includeTimestamps: Boolean = true,
    val pausePlayback: Boolean = true,
    val promptTemplate: String = DEFAULT_PROMPT,
    val keyRef: String? = null,
) {
    companion object {
        val DEFAULT_PROMPT = """
            You are an English tutor for a ${'$'}{nativeLanguage} speaker who is at level ${'$'}{level}.
            I am watching "${'$'}{title}" at ${'$'}{timestamp} and this line is hard to understand.

            Lines just before (context only, do not translate them):
            ${'$'}{context}

            The line I am asking about:
            ${'$'}{selected}

            Answer with these short sections, in this order, using markdown:
            1. Meaning here - what it means in this exact moment of the story.
            2. Why it is used - tone, register, and the effect on the listener.
            3. Near synonyms - how they differ from this wording.
            4. Where else it appears - three natural example sentences.
            Keep it under 180 words. Never lecture about grammar rules I did not ask for.
        """.trimIndent()
    }
}

@Serializable
data class TranslationSettings(
    val provider: TranslationProviderToken = TranslationProviderToken.ML_KIT_ON_DEVICE,
    val autoDownloadModels: Boolean = true,
    val cacheEnabled: Boolean = true,
    val cacheMaxEntries: Int = 4_000,
    val translateWholeBlockFirst: Boolean = true,
    val fallbackToOnline: Boolean = false,
)

@Serializable
enum class TranslationProviderToken { ML_KIT_ON_DEVICE, OFF_STUB }

/** LATER: offline dictionary import. The fields exist now so enabling the feature adds no schema break. */
@Serializable
data class DictionarySettings(
    val importEnabled: Boolean = false,
    val databaseFileKey: String? = null,
    val lookupTimeoutMs: Long = 1_500L,
    val showSections: List<String> = DEFAULT_SECTIONS,
) {
    companion object {
        val DEFAULT_SECTIONS = listOf("meanings", "synonyms", "phrasal", "collocations", "idioms", "family", "cefr", "categories")
    }
}

@Serializable
data class LanguageSettings(
    val uiLocale: String = "en",
    val learningLanguage: String = "en",
    val nativeLanguage: String = "fa",
    val followSystemLocale: Boolean = true,
)

/** Root of the typed settings tree. Bump [schemaVersion] whenever a migration is needed. */
@Serializable
data class AppSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val languages: LanguageSettings = LanguageSettings(),
    val player: PlayerSettings = PlayerSettings(),
    val subtitles: SubtitleSettings = SubtitleSettings(),
    val fonts: Map<String, FontSpec> = emptyMap(),
    val quickActions: QuickActionSettings = QuickActionSettings(),
    val gestures: GestureSettings = GestureSettings(),
    val shadowing: ShadowingSettings = ShadowingSettings(),
    val learning: LearningSettings = LearningSettings(),
    val wordStyles: WordStyleSettings = WordStyleSettings(),
    val ai: AiSettings = AiSettings(),
    val translation: TranslationSettings = TranslationSettings(),
    val dictionary: DictionarySettings = DictionarySettings(),
    val level: LevelSettings = LevelSettings(),
) {
    val fontRegistry: FontRegistry get() = FontRegistry(fonts)

    fun fontFor(surface: FontSurface, role: SubtitleLayerRole = SubtitleLayerRole.LEARNING): FontSpec =
        fontRegistry.styleFor(surface, role)

    fun subtitleLayer(role: TrackRole): SubtitleLayerSettings = subtitles.layer(role)

    companion object {
        const val SCHEMA_VERSION = 3

        val DEFAULT = AppSettings()
    }
}

/** Level data plumbing: which provider feeds "words above my level" (LRN-2). */
@Serializable
data class LevelSettings(
    val provider: LevelProviderToken = LevelProviderToken.MY_WORDS,
    /** Manual override used when the provider has no data for a word. */
    val assumedLevelOfUnknownWords: CefrLevel = CefrLevel.B2,
    val frequencyListFileKey: String? = null,
)

@Serializable
enum class LevelProviderToken { MY_WORDS, MANUAL, IMPORTED_FREQUENCY_LIST, AUTOMATIC }
