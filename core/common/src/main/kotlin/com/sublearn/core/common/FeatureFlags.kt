package com.sublearn.core.common

/**
 * Every LATER item of [docs/PRODUCT_SPEC.md] is declared here and nowhere else.
 *
 * A disabled flag must leave the NOW paths untouched: features read the flag, hide or label the
 * entry, and call the stub only inside their own code path. Flip a flag together with the
 * implementation that satisfies it; see docs/EXTENSION_POINTS.md.
 */
enum class FeatureFlag(val id: String, val specIds: String) {
    YOUTUBE("youtube", "APP entry, GEN-6"),
    PDF_LEARNING("pdf_learning", "APP entry"),
    OFFLINE_DICTIONARY("offline_dictionary", "LRN-1"),
    LEVEL_DETECTION("level_detection", "LRN-2"),
    QUIZ("quiz", "My Words"),
    UPDATE_CHECKER("update_checker", "side menu"),
    AI_RESEGMENTATION("ai_resegmentation", "SUB-6"),
    SPEECH_TO_TEXT("speech_to_text", "Subtitle engine"),
    POS_ANALYSIS("pos_analysis", "SUB-5, LRN-3"),
    ON_DEVICE_AI("on_device_ai", "AI button"),
    EXTRA_LANGUAGES("extra_languages", "Defaults"),
    ;

    companion object {
        /**
         * Compile-time map kept deliberately small and explicit. `false` means the UI shows a
         * disabled "Coming soon" entry and the stub throws [NotImplementedInThisBuild].
         */
        val enabled: Map<FeatureFlag, Boolean> = entries.associateWith { false }

        fun isEnabled(flag: FeatureFlag, overrides: Map<String, Boolean> = emptyMap()): Boolean =
            overrides[flag.id] ?: enabled[flag] ?: false
    }
}

/**
 * Thrown by LATER stubs. Never thrown by a NOW code path.
 *
 * Extends [UnsupportedOperationException] rather than `NotImplementedError`, because the stdlib type is
 * final and because callers already handle unsupported operations as a normal failure mode.
 */
class NotImplementedInThisBuild(feature: FeatureFlag) :
    UnsupportedOperationException("${feature.id} is a LATER feature. See docs/EXTENSION_POINTS.md")
