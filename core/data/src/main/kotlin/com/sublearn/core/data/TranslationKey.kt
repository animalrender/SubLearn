package com.sublearn.core.data

/** Cache key derivation for translated text. Lives in the data layer so both the store and the service agree. */
object TranslationKey {
    /** Stable, case-insensitive key: same text + languages + provider always hits. */
    fun of(text: String, sourceLanguage: String, targetLanguage: String, provider: String): String {
        val normalized = WordKey.normalize(text)
        val hash = normalized.hashCode().toLong() and 0xFFFFFFFFL
        return "$provider|$sourceLanguage|$targetLanguage|${normalized.length}|$hash"
    }
}
