package com.sublearn.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sublearn.core.settings.FontFamilyToken
import com.sublearn.core.settings.FontSpec
import com.sublearn.core.settings.TextDecorationToken

/**
 * Maps a [FontSpec] (settings, pure data) to a Compose [TextStyle]. This is the single bridge
 * between "what the user configured" and "what is drawn", which is what makes GEN-3 testable:
 * every surface asks [toTextStyle] for its own role and gets nothing else.
 */
fun FontSpec.toTextStyle(baseScale: Float = 1f): TextStyle = TextStyle(
    fontFamily = family.toFontFamily(),
    fontWeight = FontWeight(weight.weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = (sizeSp * baseScale).sp,
    letterSpacing = letterSpacingEm.em,
    lineHeight = (sizeSp * lineHeightEm * baseScale).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
    textDecoration = decoration.toCompose(),
    color = colorArgb?.let { Color(it) } ?: Color.Unspecified,
)

fun FontFamilyToken.toFontFamily(): FontFamily = when (this) {
    FontFamilyToken.SERIF -> FontFamily.Serif
    FontFamilyToken.MONOSPACE -> FontFamily.Monospace
    FontFamilyToken.CURSIVE -> FontFamily.Cursive
    FontFamilyToken.CONDENSED -> FontFamily.SansSerif
    FontFamilyToken.SANS_SERIF, FontFamilyToken.DEFAULT, FontFamilyToken.CUSTOM_FILE -> FontFamily.Default
}

fun TextDecorationToken.toCompose(): TextDecoration? = when (this) {
    TextDecorationToken.NONE, TextDecorationToken.BOX, TextDecorationToken.SOFT_SHADOW -> null
    TextDecorationToken.UNDERLINE -> TextDecoration.Underline
    TextDecorationToken.DOTTED_UNDERLINE -> TextDecoration.Underline
    TextDecorationToken.DASHED_UNDERLINE -> TextDecoration.Underline
    TextDecorationToken.STRIKETHROUGH -> TextDecoration.LineThrough
    TextDecorationToken.OUTLINE -> null
}

fun FontSpec.colorOrNull(): Color? = colorArgb?.let { Color(it) }

fun FontSpec.backgroundOrNull(): Color? = backgroundColorArgb?.let { Color(it) }
