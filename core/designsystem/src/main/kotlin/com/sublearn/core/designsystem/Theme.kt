package com.sublearn.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.FontFamilyToken
import com.sublearn.core.settings.AccentToken
import com.sublearn.core.settings.FontSurface
import com.sublearn.core.settings.SubtitleLayerRole
import com.sublearn.core.settings.ThemeMode

/**
 * Theme entry point (GEN-1, GEN-2).
 *
 * Design direction: "dark-first learning canvas". The video is the hero, so all chrome is
 * translucent glass over a scrim, the accent is used only for state (current line, active toggle),
 * and the subtitle layers own the strongest contrast. Light theme exists for the settings and
 * My Words screens, AMOLED replaces every dark surface with pure black for the player.
 */
@Composable
fun SubLearnTheme(
    settings: AppSettings = AppSettings.DEFAULT,
    content: @Composable () -> Unit,
) {
    val appearance = settings.appearance
    val context = LocalContext.current
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val amoled = appearance.themeMode == ThemeMode.AMOLED
    val accent = remember(appearance.accent, dark) { accentColor(appearance.accent, dark) }

    val scheme = remember(dark, amoled, accent, appearance.useDynamicColor) {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appearance.useDynamicColor) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) {
            darkColorScheme(
                primary = accent,
                onPrimary = Color(Palette.DARK_BACKGROUND),
                secondary = accent.copy(alpha = 0.85f),
                background = Color(if (amoled) Palette.AMOLED_BACKGROUND else Palette.DARK_BACKGROUND),
                onBackground = Color(0xFFE8EEF2L),
                surface = Color(if (amoled) Palette.AMOLED_BACKGROUND else Palette.DARK_SURFACE),
                onSurface = Color(0xFFE8EEF2L),
                surfaceVariant = Color(Palette.DARK_SURFACE_ALT),
                onSurfaceVariant = Color(0xFFAEBCC4L),
                outline = Color(0xFF6B7A83L),
                outlineVariant = Color(0xFF2A333AL),
            )
        } else {
            lightColorScheme(
                primary = accent,
                secondary = accent,
                background = Color(Palette.LIGHT_BACKGROUND),
                surface = Color(Palette.LIGHT_SURFACE),
                outline = Color(0xFF6B7A83L),
            )
        }
        if (amoled && dark) {
            base.copy(
                surfaceContainer = Color(Palette.AMOLED_BACKGROUND),
                surfaceContainerLow = Color(Palette.AMOLED_BACKGROUND),
                surfaceContainerHigh = Color(Palette.AMOLED_BACKGROUND),
                surfaceContainerHighest = Color(Palette.AMOLED_BACKGROUND),
            )
        } else {
            base
        }
    }

    val menuSpec = remember(settings) { settings.fontFor(FontSurface.APP_MENUS, SubtitleLayerRole.LEARNING) }
    val typography = remember(menuSpec) { buildAppTypography(menuSpec) }
    val semantic = remember(scheme, dark, amoled) { semanticColors(dark, amoled) }

    CompositionLocals(
        scheme = scheme,
        typography = typography,
        semantic = semantic,
        reduceMotion = appearance.reduceMotion,
        fontScale = appearance.appFontScale,
    ) {
        content()
    }
}

@Composable
private fun CompositionLocals(
    scheme: androidx.compose.material3.ColorScheme,
    typography: Typography,
    semantic: SubLearnColors,
    reduceMotion: Boolean,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSubLearnColors provides semantic,
        LocalReduceMotion provides reduceMotion,
        LocalAppFontScale provides fontScale,
        content = { MaterialTheme(colorScheme = scheme, typography = typography, content = content) },
    )
}

private fun accentColor(token: AccentToken, dark: Boolean): Color {
    val pair = Palette.accent(token.name)
    return Color(if (dark) pair.dark else pair.light)
}

private fun semanticColors(dark: Boolean, amoled: Boolean): SubLearnColors = SubLearnColors(
    playerScrim = Color(if (dark) Palette.SCRIM_PLAYER else Palette.SCRIM_SOFT),
    subtitleBackdrop = Color(if (amoled) Palette.BACKDROP_STRONG else Palette.BACKDROP),
    subtitleText = Color(0xFFF5F8FAL),
    letterbox = Color(0xFF000000L),
    cardBackdrop = Color(if (dark) Palette.BACKDROP else Palette.LIGHT_SURFACE),
    strongBackdrop = Color(Palette.BACKDROP_STRONG),
    overlayOutline = Color(Palette.OUTLINE_SUBTITLE),
    controlIdle = if (dark) Color(0xFFE8EEF2L) else Color(0xFF14181DL),
    controlActive = Color(Palette.accent("TEAL").let { if (dark) it.dark else it.light }),
    controlDisabled = Color(0x669FB0BFL),
    listCurrentRow = if (dark) Color(0x24FFFFFFL) else Color(0x14000000L),
    listHoverRow = if (dark) Color(0x14FFFFFFL) else Color(0x0A000000L),
    levelBadgeBackgroundAlpha = BADGE_ALPHA,
)

/**
 * Builds the Material [Typography] from the APP_MENUS font spec so that changing that one setting
 * retunes menus, lists and dialogs while leaving subtitle surfaces untouched (GEN-3).
 */
private fun buildAppTypography(spec: com.sublearn.core.settings.FontSpec): Typography {
    val base = spec.toTextStyle()
    fun scale(factor: Float, weight: androidx.compose.ui.text.font.FontWeight? = null): TextStyle = base.copy(
        fontSize = (spec.sizeSp * factor).sp,
        fontWeight = weight ?: base.fontWeight,
        letterSpacing = spec.letterSpacingEm.em,
    )

    return Typography(
        displaySmall = scale(2.0f, androidx.compose.ui.text.font.FontWeight.Bold),
        headlineMedium = scale(1.5f, androidx.compose.ui.text.font.FontWeight.Bold),
        headlineSmall = scale(1.3f, androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleLarge = scale(1.15f, androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleMedium = scale(1.0f, androidx.compose.ui.text.font.FontWeight.Medium),
        titleSmall = scale(0.9f, androidx.compose.ui.text.font.FontWeight.Medium),
        bodyLarge = scale(1.0f, androidx.compose.ui.text.font.FontWeight.Normal),
        bodyMedium = scale(0.92f, androidx.compose.ui.text.font.FontWeight.Normal),
        bodySmall = scale(0.82f, androidx.compose.ui.text.font.FontWeight.Normal),
        labelLarge = scale(0.92f, androidx.compose.ui.text.font.FontWeight.Medium),
        labelMedium = scale(0.8f, androidx.compose.ui.text.font.FontWeight.Medium),
        labelSmall = scale(0.72f, androidx.compose.ui.text.font.FontWeight.Normal),
    )
}

val LocalSubLearnColors: ProvidableCompositionLocal<SubLearnColors> = compositionLocalOf {
    semanticColors(dark = true, amoled = false)
}
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
val LocalAppFontScale: ProvidableCompositionLocal<Float> = compositionLocalOf { 1f }

/** `FontFamily` for a token, used by callers that need the family without a full style. */
fun fontFamilyOf(token: FontFamilyToken): FontFamily = token.toFontFamily()
