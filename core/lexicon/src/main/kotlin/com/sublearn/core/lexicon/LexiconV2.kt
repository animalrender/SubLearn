package com.sublearn.core.lexicon

/**
 * Lexicon pack v2: the SubLearn-native format for the English-Persian dictionary.
 *
 * Built by `tools/lexicon_builder/build_lexicon.py` from the owner's database.
 * The schema is documented in `docs/EXTENSION_POINTS.md`.
 *
 * This file contains the data classes that mirror the v2 SQLite schema.
 * The actual SQLite reading happens in `core:data` via `SqliteLexiconSource`.
 */

/** A word, phrasal verb, idiom or collocation entry in the lexicon. */
data class LexiconV2Entry(
    val id: Long,
    val key: String,
    val headword: String,
    val kind: LexiconKind,
    val pos: String?,
    val cefr: String?,
    val register: String?,
)

enum class LexiconKind {
    WORD,
    PHRASAL,
    IDIOM,
    COLLOCATION,
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
    val register: String?,
    val isPrimary: Boolean,
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

/** A lemma form mapping (inflected form → lemma). */
data class LemmaFormEntry(
    val form: String,
    val lemma: String,
    val pos: String?,
)

/** Metadata about the lexicon pack. */
data class LexiconV2Meta(
    val formatVersion: Int,
    val sourceLang: String,
    val glossLang: String,
    val entryCount: Int,
    val builtAt: String,
    val builderVersion: String,
)

/**
 * Result of importing a lexicon pack.
 */
data class LexiconImportResult(
    val success: Boolean,
    val meta: LexiconV2Meta? = null,
    val warnings: List<String> = emptyList(),
    val errorMessage: String? = null,
)
