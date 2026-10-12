package com.sublearn.core.lexicon

import com.sublearn.core.common.AppResult
import com.sublearn.core.settings.CefrLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real offline [DictionaryProvider] backed by [PackLexiconAccess] (`LEXICON_EN_FA`, `DICT_FA_EN`,
 * and `LEGACY_V1`, Phase 10, D-58).
 */
class ImportedDictionaryProvider(
    private val lexiconAccess: PackLexiconAccess,
) : DictionaryProvider {

    override suspend fun lookup(word: String): AppResult<DictionaryEntry?> = withContext(Dispatchers.IO) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return@withContext AppResult.success(null)
        lexiconAccess.ensureInitialized()

        val isPersian = trimmed.any { it in '\u0600'..'\u06FF' }
        val entry = if (isPersian) {
            lexiconAccess.lookupReverseFaEn(trimmed)
        } else {
            lexiconAccess.lookupFullEntry(trimmed) ?: lexiconAccess.lookupLegacyV1(trimmed)
        }
        AppResult.success(entry)
    }

    override suspend fun search(query: String, limit: Int): AppResult<List<DictionaryEntry>> = withContext(Dispatchers.IO) {
        lexiconAccess.ensureInitialized()
        AppResult.success(lexiconAccess.searchDictionary(query, limit))
    }

    override suspend fun isAvailable(): Boolean {
        lexiconAccess.ensureInitialized()
        return lexiconAccess.hasActiveEnFaPack() || lexiconAccess.hasActiveFaEnPack() || lexiconAccess.hasActiveLegacyV1Pack()
    }
}

/**
 * [WordLevelProvider] backed by CEFR tags in the active lexicon pack (`LEXICON_EN_FA`).
 */
class LexiconLevelProvider(
    private val lexiconAccess: PackLexiconAccess,
) : WordLevelProvider {
    override val id: String = "lexicon_v2"

    override suspend fun level(word: String): CefrLevel? {
        lexiconAccess.ensureInitialized()
        if (!lexiconAccess.hasActiveEnFaPack()) return null
        val entry = lexiconAccess.lookupWord(word) ?: run {
            val lemmas = lexiconAccess.lemmatiser(word)
            lemmas.firstNotNullOfOrNull { lexiconAccess.lookupWord(it) }
        }
        val cefrStr = entry?.cefr ?: return null
        return CefrLevel.entries.firstOrNull { it.name.equals(cefrStr, ignoreCase = true) }
    }
}
