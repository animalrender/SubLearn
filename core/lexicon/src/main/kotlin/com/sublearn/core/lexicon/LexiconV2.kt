package com.sublearn.core.lexicon

/**
 * Lexicon pack v2: the SubLearn-native format for the English-Persian and Persian-English dictionaries.
 *
 * Built by `tools/lexicon_builder/build_lexicon.py` from the owner's database.
 * The schema is documented in `docs/EXTENSION_POINTS.md` (§ 6.1, D-43).
 */

/** Kind of installed dictionary or data pack (D-58). */
enum class PackKind(val id: String) {
    LEXICON_EN_FA("LEXICON_EN_FA"),
    DICT_FA_EN("DICT_FA_EN"),
    LEGACY_V1("LEGACY_V1"),
    WORDFREQ_CEFR("WORDFREQ_CEFR"),
    ;

    companion object {
        fun fromId(id: String?): PackKind? = entries.firstOrNull { it.id == id || it.name == id }
    }
}

/** A word, phrasal verb, idiom, collocation, or phrase entry in the lexicon. */
data class LexiconV2Entry(
    val id: Long,
    val key: String,
    val headword: String,
    val kind: LexiconKind,
    val pos: String?,
    val cefr: String?,
    val register: String?,
    val prior: Float = 0.5f,
)

enum class LexiconKind {
    WORD,
    PHRASAL,
    IDIOM,
    COLLOCATION,
    PHRASE,
    ;

    companion object {
        fun fromKey(value: String?): LexiconKind = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WORD
    }
}

/** A meaning/sense of a lexicon entry. */
data class LexiconV2Sense(
    val id: Long,
    val entryId: Long,
    val position: Int,
    val pos: String?,
    val glossFa: String,
    val glossEn: String?,
    val domain: String?,
    val domainFa: String? = domain,
    val domainEn: String? = null,
    val register: String?,
    val cefr: String? = null,
    val isPrimary: Boolean,
    val prior: Float = 0.5f,
)

/** An example sentence for a sense. */
data class LexiconV2Example(
    val senseId: Long,
    val en: String?,
    val fa: String?,
)

/** A pattern for matching multi-word expressions. */
data class LexiconV2Pattern(
    val entryId: Long,
    val pattern: String,
    val anchorLemma: String,
    val separable: Boolean,
    val minTokens: Int,
    val maxTokens: Int,
)

/** A relation between entries (synonym, antonym, word family). */
data class LexiconV2Relation(
    val entryId: Long,
    val kind: RelationKind,
    val target: String,
)

enum class RelationKind {
    SYNONYM,
    ANTONYM,
    WORD_FAMILY,
}

/** Compact extra data stored in `entry_extra` (`' | '`-joined strings, max 20 items per field). */
data class LexiconV2Extra(
    val entryId: Long,
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val notes: String? = null,
)

/** Pronunciation row in `pron`. */
data class LexiconV2Pron(
    val entryId: Long,
    val ipaUs: String? = null,
    val ipaUk: String? = null,
    val audioUs: String? = null,
    val audioUk: String? = null,
)

/** A lemma form mapping (inflected form → lemma) with rank (0 = primary lemma, 1 = homograph self-lemma). */
data class LemmaFormEntry(
    val form: String,
    val lemma: String,
    val pos: String?,
    val rank: Int = 0,
)

/** Metadata about the lexicon pack. */
data class LexiconV2Meta(
    val formatVersion: Int,
    val packId: String = "lexicon-en-fa-v2",
    val direction: String = "en-fa",
    val sourceLang: String,
    val glossLang: String,
    val entryCount: Int,
    val senseCount: Int = 0,
    val patternCount: Int = 0,
    val lemmaCount: Int = 0,
    val builtAt: String,
    val builderVersion: String,
)

/** Result of importing a lexicon pack. */
data class LexiconImportResult(
    val success: Boolean,
    val packId: String? = null,
    val kind: PackKind? = null,
    val meta: LexiconV2Meta? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)
