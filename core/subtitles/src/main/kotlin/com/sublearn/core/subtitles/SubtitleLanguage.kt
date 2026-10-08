package com.sublearn.core.subtitles

/**
 * Guesses the language of a subtitle file from its name so a folder drop lands on the right
 * layer, and maps user-visible names to the codes the translation stack needs.
 *
 * Deliberately conservative: when the name says nothing the guess is null and the user chooses.
 */
object SubtitleLanguage {
    /** Learning side of the default configuration. */
    const val DEFAULT_LEARNING = "en"

    /** Native/translation side of the default configuration. */
    const val DEFAULT_NATIVE = "fa"

    private val aliases: Map<String, String> = buildMap {
        listOf("en", "eng", "english").forEach { put(it, "en") }
        listOf("fa", "per", "pes", "farsi", "persian", "prs").forEach { put(it, "fa") }
        listOf("ar", "ara", "arabic").forEach { put(it, "ar") }
        listOf("es", "spa", "spanish", "español").forEach { put(it, "es") }
        listOf("fr", "fra", "fre", "french").forEach { put(it, "fr") }
        listOf("de", "deu", "ger", "german").forEach { put(it, "de") }
        listOf("tr", "tur", "turkish").forEach { put(it, "tr") }
        listOf("id", "ind", "indonesian").forEach { put(it, "id") }
        listOf("vi", "vie", "vietnamese").forEach { put(it, "vi") }
        listOf("zh", "chi", "zho", "chinese").forEach { put(it, "zh") }
        listOf("ja", "jpn", "japanese").forEach { put(it, "ja") }
        listOf("ko", "kor", "korean").forEach { put(it, "ko") }
        listOf("ru", "rus", "russian").forEach { put(it, "ru") }
        listOf("pt", "por", "portuguese").forEach { put(it, "pt") }
        listOf("hi", "hin", "hindi").forEach { put(it, "hi") }
    }

    /** ISO-like codes that mean "no translation" for the purposes of the layer guess. */
    private val commentaryMarkers = listOf("commentary", "comments", "directors", "extras", "forced", "sdh", "cc")

    fun normalize(token: String): String? = aliases[token.lowercase().trim()]

    /** Extracts `en` from `Movie.eng.srt`, `Persian` from `movie.persian.sub`, null when unclear. */
    fun guessFromName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        val pieces = stem.split(Regex("""[._\-\[\]()]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.any { piece -> commentaryMarkers.any { piece.equals(it, ignoreCase = true) } }) return null
        pieces.forEach { piece ->
            // Skip pure quality/channel tokens: 1080p, 720p, x264, WEB-DL, ...
            if (piece.matches(Regex("""(?i)\d{3,4}p?"""))) return@forEach
            normalize(piece)?.let { return it }
        }
        return null
    }

    /** True when the tag should default to the translation (native) layer. */
    fun isNativeSide(tag: String?, nativeTag: String = DEFAULT_NATIVE): Boolean =
        tag != null && tag.equals(nativeTag, ignoreCase = true)

    fun displayName(tag: String?): String = when (tag) {
        null -> "Unknown"
        "en" -> "English"
        "fa" -> "Persian"
        "ar" -> "Arabic"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "tr" -> "Turkish"
        "id" -> "Indonesian"
        "vi" -> "Vietnamese"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "ru" -> "Russian"
        "pt" -> "Portuguese"
        "hi" -> "Hindi"
        else -> tag
    }
}
