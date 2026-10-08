package com.sublearn.core.settings

import kotlinx.serialization.Serializable

/**
 * A place in the app where text is drawn. GEN-3 requires font style per language role *and* per
 * surface, so a surface, not the app, owns the visual contract.
 */
@Serializable
enum class FontSurface {
    APP_MENUS,
    SUBTITLE_LEARNING,
    SUBTITLE_TRANSLATION,
    TRANSLATION_POPUP,
    WORD_CARD,
    AI_ANSWER,
    SUBTITLE_LIST,
    PLAYER_CHROME,
    LEARNING_POPUP,
    ;

    companion object {
        fun fromKey(key: String): FontSurface? = entries.firstOrNull { it.name == key }
    }
}

/** Families available without shipping a font binary (see the license audit in THIRD_PARTY_NOTICES). */
@Serializable
enum class FontFamilyToken {
    DEFAULT,
    SANS_SERIF,
    SERIF,
    MONOSPACE,
    CONDENSED,
    CURSIVE,
    CUSTOM_FILE,
    ;

    companion object {
        val systemTokens = listOf(DEFAULT, SANS_SERIF, SERIF, MONOSPACE, CONDENSED)
    }
}

@Serializable
enum class FontWeightToken {
    THIN,
    LIGHT,
    REGULAR,
    MEDIUM,
    SEMIBOLD,
    BOLD,
    BLACK,
    ;

    val weight: Int
        get() = when (this) {
            THIN -> 100
            LIGHT -> 300
            REGULAR -> 400
            MEDIUM -> 500
            SEMIBOLD -> 600
            BOLD -> 700
            BLACK -> 900
        }
}

@Serializable
enum class TextDecorationToken {
    NONE,
    UNDERLINE,
    DOTTED_UNDERLINE,
    DASHED_UNDERLINE,
    STRIKETHROUGH,
    OUTLINE,
    BOX,
    SOFT_SHADOW,
}

/**
 * One complete text style. Colours are `0xAARRGGBB` integers; null means "take the theme colour",
 * which keeps surfaces themed while still allowing an explicit override (GEN-2).
 */
@Serializable
data class FontSpec(
    val family: FontFamilyToken = FontFamilyToken.DEFAULT,
    val customFamilyPath: String? = null,
    val sizeSp: Float = 20f,
    val weight: FontWeightToken = FontWeightToken.SEMIBOLD,
    val italic: Boolean = false,
    val colorArgb: Long? = null,
    val backgroundColorArgb: Long? = null,
    val backgroundAlpha: Float = 0.55f,
    val cornerRadiusDp: Float = 10f,
    val outlineAlpha: Float = 0.85f,
    val decoration: TextDecorationToken = TextDecorationToken.NONE,
    val letterSpacingEm: Float = 0f,
    val lineHeightEm: Float = 1.25f,
    val maxLines: Int = 4,
    val shadowElevationDp: Float = 0f,
    val allCaps: Boolean = false,
) {
    init {
        require(sizeSp > 0f) { "font size must be positive" }
        require(backgroundAlpha in 0f..1f) { "backgroundAlpha is a fraction" }
    }

    fun mergedWith(other: FontSpec?): FontSpec = other ?: this

    companion object {
        /** Default style for a surface/role pair; the single source of truth for untouched settings. */
        fun defaultFor(surface: FontSurface, role: SubtitleLayerRole): FontSpec {
            val isNative = role == SubtitleLayerRole.NATIVE
            val base = when (surface) {
                FontSurface.APP_MENUS -> FontSpec(
                    family = FontFamilyToken.DEFAULT,
                    sizeSp = 15f,
                    weight = FontWeightToken.REGULAR,
                    lineHeightEm = 1.4f,
                )
                FontSurface.PLAYER_CHROME -> FontSpec(
                    family = FontFamilyToken.DEFAULT,
                    sizeSp = 13f,
                    weight = FontWeightToken.MEDIUM,
                    colorArgb = 0xFFFFFFFF,
                    lineHeightEm = 1.2f,
                )
                FontSurface.SUBTITLE_LEARNING -> FontSpec(
                    sizeSp = if (isNative) 22f else 26f,
                    weight = if (isNative) FontWeightToken.MEDIUM else FontWeightToken.BOLD,
                    colorArgb = 0xFFFFFFFF,
                    outlineAlpha = 0.9f,
                    decoration = TextDecorationToken.SOFT_SHADOW,
                    lineHeightEm = 1.2f,
                    maxLines = 3,
                )
                FontSurface.SUBTITLE_TRANSLATION -> FontSpec(
                    sizeSp = if (isNative) 20f else 20f,
                    weight = FontWeightToken.REGULAR,
                    colorArgb = 0xFFFFD98A,
                    outlineAlpha = 0.7f,
                    lineHeightEm = 1.2f,
                    maxLines = 3,
                )
                FontSurface.TRANSLATION_POPUP -> FontSpec(
                    sizeSp = 20f,
                    weight = FontWeightToken.MEDIUM,
                    backgroundColorArgb = 0xFF101418,
                    backgroundAlpha = 0.94f,
                    cornerRadiusDp = 16f,
                    lineHeightEm = 1.35f,
                    maxLines = 10,
                )
                FontSurface.WORD_CARD -> FontSpec(
                    sizeSp = 22f,
                    weight = FontWeightToken.BOLD,
                    backgroundColorArgb = 0xFF101418,
                    backgroundAlpha = 0.96f,
                    cornerRadiusDp = 22f,
                    shadowElevationDp = 18f,
                    lineHeightEm = 1.3f,
                    maxLines = 14,
                )
                FontSurface.AI_ANSWER -> FontSpec(
                    sizeSp = 16f,
                    weight = FontWeightToken.REGULAR,
                    backgroundColorArgb = 0xFF0D1116,
                    backgroundAlpha = 0.97f,
                    cornerRadiusDp = 20f,
                    lineHeightEm = 1.45f,
                    maxLines = 40,
                )
                FontSurface.SUBTITLE_LIST -> FontSpec(
                    sizeSp = 15f,
                    weight = FontWeightToken.REGULAR,
                    lineHeightEm = 1.35f,
                    maxLines = 6,
                )
                FontSurface.LEARNING_POPUP -> FontSpec(
                    sizeSp = 16f,
                    weight = FontWeightToken.SEMIBOLD,
                    backgroundColorArgb = 0xFF0F1418,
                    backgroundAlpha = 0.8f,
                    cornerRadiusDp = 18f,
                    shadowElevationDp = 12f,
                    lineHeightEm = 1.25f,
                    maxLines = 3,
                )
            }
            // The native (usually Persian) role gets a slightly tighter line box and a naskh-ish
            // fallback so Arabic script renders with correct shaping on every device.
            return if (isNative && surface != FontSurface.APP_MENUS) {
                base.copy(lineHeightEm = (base.lineHeightEm * 1.18f), letterSpacingEm = 0f)
            } else {
                base
            }
        }
    }
}

/** Keys of the font map: a surface plus the language role the text run belongs to. */
@Serializable
enum class SubtitleLayerRole {
    LEARNING,
    NATIVE,
    ;

    companion object {
        fun fromKey(key: String): SubtitleLayerRole = entries.firstOrNull { it.name == key } ?: LEARNING
    }
}

fun fontKey(surface: FontSurface, role: SubtitleLayerRole): String = "${surface.name}:${role.name}"

/**
 * Resolves a [FontSpec] for a surface/role pair. Overrides never bleed: a lookup for one surface
 * can only ever read that surface's key (GEN-3), and unknown keys fall back to the defaults.
 */
class FontRegistry(private val overrides: Map<String, FontSpec>) {
    fun styleFor(surface: FontSurface, role: SubtitleLayerRole): FontSpec =
        overrides[fontKey(surface, role)] ?: FontSpec.defaultFor(surface, role)

    /** True when the user has an explicit override for this pair; drives the "Reset" affordance. */
    fun hasOverride(surface: FontSurface, role: SubtitleLayerRole): Boolean = overrides.containsKey(fontKey(surface, role))

    fun overrideFor(surface: FontSurface, role: SubtitleLayerRole): FontSpec? = overrides[fontKey(surface, role)]

    fun withOverride(surface: FontSurface, role: SubtitleLayerRole, spec: FontSpec): Map<String, FontSpec> =
        overrides + (fontKey(surface, role) to spec)

    fun withoutOverride(surface: FontSurface, role: SubtitleLayerRole): Map<String, FontSpec> =
        overrides - fontKey(surface, role)
}
