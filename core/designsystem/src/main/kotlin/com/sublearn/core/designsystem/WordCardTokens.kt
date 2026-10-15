package com.sublearn.core.designsystem

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the word/phrase touch popup card, matching the ProudVocab visual style.
 *
 * Source: CSS/JS ProudVocab reference — numbers only, no code ported (D-39).
 * All values are in dp/sp as appropriate. Colors are Long hex values for Compose.
 *
 * §7 of the current phase specifies these tokens precisely.
 */
object WordCardTokens {

    // --- Container ---
    /** Background: rgba(10,15,30,0.85) */
    const val CONTAINER_BG = 0xD90A0F1EL
    /** Border: 1.5dp rgba(148,163,184,0.25) */
    const val CONTAINER_BORDER = 0x4094A3B8L
    val CONTAINER_RADIUS = 12.dp
    val CONTAINER_PADDING_H = 12.dp
    val CONTAINER_PADDING_V = 8.dp
    val CONTAINER_MIN_WIDTH = 150.dp
    val CONTAINER_MAX_WIDTH = 300.dp // was 520, reduced for compact card
    /** Shadow: ≈ 0 8 32 rgba(0,0,0,.5) */
    const val CONTAINER_SHADOW_COLOR = 0x80000000L
    val CONTAINER_SHADOW_RADIUS = 32.dp
    val CONTAINER_SHADOW_OFFSET_Y = 8.dp

    // --- Word row ---
    val WORD_TEXT_SIZE = 15.sp
    const val WORD_TEXT_WEIGHT = 800
    const val WORD_LETTER_SPACING = -0.01f // em
    // Word color = CEFR level color (from Palette.levelColor)

    val TRANSLATION_TEXT_SIZE = 14.sp
    const val TRANSLATION_TEXT_WEIGHT = 700
    const val TRANSLATION_COLOR = 0xFFF1F5F9L
    const val TRANSLATION_LINE_HEIGHT = 1.35f // multiplier
    val TRANSLATION_TOP_MARGIN = 3.dp

    // --- Phrase row ---
    val PHRASE_DIVIDER_COLOR = 0x1FFFFFFFL // white@12%
    val PHRASE_TOP_MARGIN = 8.dp
    val PHRASE_TOP_PADDING = 8.dp
    val PHRASE_TEXT_SIZE: TextUnit = 14.5.sp
    const val PHRASE_TEXT_WEIGHT = 800
    // Phrase text color = type color (Phrasal/Idiom/COL)

    /** Maximum number of phrase alternative rows shown (from PhraseSpan.alternatives). */
    const val MAX_PHRASE_ROWS = 2

    // --- Badge (CEFR level or phrase type) ---
    val BADGE_TEXT_SIZE = 9.5.sp
    const val BADGE_TEXT_WEIGHT = 800
    const val BADGE_LETTER_SPACING = 0.03f // em
    val BADGE_RADIUS = 4.dp
    val BADGE_PADDING_H = 5.dp
    val BADGE_PADDING_V = 1.dp
    val BADGE_BORDER_WIDTH = 1.dp
    const val BADGE_BG_ALPHA = 0.13f // 13%
    const val BADGE_BORDER_ALPHA = 0.33f // 33%

    // --- Save button ---
    val SAVE_BUTTON_SIZE = 28.dp
    val SAVE_BUTTON_RADIUS = 7.dp
    const val SAVE_BUTTON_BG = 0x14FFFFFFL // white@8%
    const val SAVE_BUTTON_BORDER = 0x29FFFFFFL // white@16%
    val SAVE_ICON_SIZE = 14.dp
    const val SAVE_ICON_COLOR = 0xFFCBD5E1L // slate-300
    /** Invisible touch area for accessibility (minimum 40dp). */
    val SAVE_TOUCH_TARGET = 40.dp

    // --- Phrase type colors (underline and text) ---
    const val COLOR_PHRASAL = 0xFFC084FCL // purple-400
    const val COLOR_IDIOM = 0xFFFB923CL   // orange-400
    const val COLOR_COLLOC = 0xFF38BDF8L  // sky-400

    // --- CEFR level colors ---
    const val LEVEL_A1 = 0xFF22C55EL // green-500
    const val LEVEL_A2 = 0xFF84CC16L // lime-500
    const val LEVEL_B1 = 0xFFEAB308L // yellow-500
    const val LEVEL_B2 = 0xFFF97316L // orange-500
    const val LEVEL_C1 = 0xFFEF4444L // red-500
    const val LEVEL_C2 = 0xFFA855F7L // purple-500
    const val LEVEL_UNKNOWN = 0xFF94A3B8L // slate-400

    // --- Type labels (EN / FA — actual strings in strings.xml, these are fallback keys) ---
    // EN: "Phrasal" / "Idiom" / "COL"
    // FA: "فعل عبارتی" / "اصطلاح" / "هم‌نشینی"

    /**
     * Get the phrase type color for a given phrase kind string.
     */
    fun colorForType(type: String): Long = when (type.uppercase()) {
        "PHRASAL" -> COLOR_PHRASAL
        "IDIOM" -> COLOR_IDIOM
        "COLLOCATION", "COL" -> COLOR_COLLOC
        else -> LEVEL_UNKNOWN
    }

    /**
     * Get the CEFR level color for a given level string.
     */
    fun colorForLevel(level: String): Long = when (level.uppercase()) {
        "A1" -> LEVEL_A1
        "A2" -> LEVEL_A2
        "B1" -> LEVEL_B1
        "B2" -> LEVEL_B2
        "C1" -> LEVEL_C1
        "C2" -> LEVEL_C2
        else -> LEVEL_UNKNOWN
    }
}