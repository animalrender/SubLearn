package com.sublearn.core.designsystem

/**
 * The only place raw colour values are allowed (ENGINEERING REQUIREMENTS: design tokens only).
 *
 * The palette is intentionally dark-first: a subtitle player is mostly a black canvas, and the
 * learning layer must win contrast against any frame. Accents are desaturated slightly so a
 * saturated subtitle colour never competes with the UI.
 */
object Palette {
    const val TRANSPARENT = 0x00000000L
    const val SCRIM_SOFT = 0x66000000L
    const val SCRIM_PLAYER = 0xB3000000L
    const val BACKDROP = 0xCC101418L
    const val BACKDROP_STRONG = 0xF20D1116L
    const val OUTLINE_SUBTITLE = 0xE6000000L

    const val DARK_SURFACE = 0xFF0C1014L
    const val DARK_SURFACE_ALT = 0xFF141A20L
    const val DARK_BACKGROUND = 0xFF070A0DL
    const val AMOLED_BACKGROUND = 0xFF000000L
    const val LIGHT_SURFACE = 0xFFFFFFFFL
    const val LIGHT_BACKGROUND = 0xFFF6F8FAL
    const val LIGHT_ON = 0xFF14181DL

    fun accent(token: String): AccentPair = when (token) {
        "INDIGO" -> AccentPair(0xFF7C9BFFL, 0xFF3555C9L)
        "AMBER" -> AccentPair(0xFFFFC46BL, 0xFF8A5A00L)
        "ROSE" -> AccentPair(0xFFFF8FA8L, 0xFF9B2C50L)
        "FOREST" -> AccentPair(0xFF7FE3A8L, 0xFF146C43L)
        "VIOLET" -> AccentPair(0xFFC6A8FFL, 0xFF5B34B4L)
        "SKY" -> AccentPair(0xFF8FD6FFL, 0xFF0A6391L)
        else -> AccentPair(0xFF6FE3D2L, 0xFF00695FL)
    }

    /** CEFR badge colours, reused by My Words and the learning popups. */
    fun levelColor(level: String): Long = when (level.uppercase()) {
        "A1" -> 0xFF7BE3A1L
        "A2" -> 0xFF5FD6B8L
        "B1" -> 0xFF6FC5FFL
        "B2" -> 0xFF9AA8FFL
        "C1" -> 0xFFC79BFFL
        "C2" -> 0xFFF199D0L
        "PHRASAL" -> 0xFFFFB86BL
        "IDIOM" -> 0xFFFF8FA8L
        "COL" -> 0xFFFFE082L
        else -> 0xFF9FB0BFL
    }

    data class AccentPair(val dark: Long, val light: Long)
}

/** Spacing, radii and sizing scale. Everything is a multiple of 4 dp except text paddings. */
object Dimens {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    val radiusSm = 8.dp
    val radiusMd = 14.dp
    val radiusLg = 22.dp
    val radiusPill = 999.dp

    val touchTarget = 44.dp
    val iconButton = 40.dp
    val iconButtonLarge = 52.dp
    val playerEdgeInset = 12.dp
    val subtitleBottomSafe = 24.dp
    val listPanelMinWidth = 260.dp
    val listPanelMaxWidth = 420.dp
    /** Portrait: the subtitle list takes this share of the height and the picture keeps the rest. */
    val listPortraitShare = 0.45f
    val cardElevation = 6.dp
    val strokeWidth = 1.5.dp

    // Player chrome. Named here so a feature never types a literal size (DESIGN_SYSTEM).
    val xxs = 2.dp
    val spinnerStroke = 2.dp
    val spinnerSmall = 16.dp
    val inlineIcon = 18.dp
    val pillHeight = 32.dp
    val topBarHeight = 56.dp
    val quickColumnTop = 64.dp
    val bottomChromeReserve = 128.dp
    val snackbarBottom = 148.dp
    val cardMaxWidth = 520.dp
    val cardMaxHeight = 360.dp
    val menuMaxWidth = 240.dp
    val bannerMaxWidth = 480.dp
    val seekbarTrack = 4.dp
    val seekbarThumb = 14.dp
    val hudTrackWidth = 6.dp
    val hudTrackHeight = 160.dp
    val hudCircle = 72.dp
    val hudPadding = 24.dp
    val dockGap = 4.dp
}

/** Minimal dp/span so this module does not need to import foundation everywhere. */
val Int.dp: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

/** Hairlines and strokes are fractional, and a literal like `1.5.dp` is a Double, not a Float. */
val Double.dp: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
