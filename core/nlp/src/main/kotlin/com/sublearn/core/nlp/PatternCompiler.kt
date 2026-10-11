package com.sublearn.core.nlp

/**
 * Compiles pattern strings from the lexicon into matchers that can be applied to token sequences.
 *
 * Pattern syntax:
 * - Literal words match after lemmatisation
 * - `POSS` matches any possessive pronoun (my/your/his/her/its/our/their/'s)
 * - `REFL` matches any reflexive pronoun (myself/yourself/himself/...)
 * - `{NP}` matches a noun-phrase slot (1-3 tokens, determiner + optional adjectives + noun)
 * - Words wrapped in `(or ...)` or `(all)` are optional
 *
 * This is pure Kotlin with no Android dependencies (D-41).
 */
object PatternCompiler {

    /**
     * Compile a pattern string into a list of [PatternElement]s.
     */
    fun compile(pattern: String): List<PatternElement> {
        val tokens = pattern.lowercase().split("\\s+".toRegex())
        return tokens.mapNotNull { token ->
            when {
                token.isBlank() -> null
                token == "poss" -> PatternElement.Possessive
                token == "refl" -> PatternElement.Reflexive
                token == "{np}" -> PatternElement.NounPhrase
                token == "be" || token == "am" || token == "is" || token == "are" ||
                    token == "was" || token == "were" || token == "been" -> PatternElement.BeVerb
                else -> PatternElement.Literal(token)
            }
        }
    }
}

/**
 * One element of a compiled pattern.
 */
sealed class PatternElement {
    /** A literal word that matches after lemmatisation. */
    data class Literal(val word: String) : PatternElement()

    /** Matches any possessive: my/your/his/her/its/our/their/'s. */
    object Possessive : PatternElement()

    /** Matches any reflexive pronoun. */
    object Reflexive : PatternElement()

    /** Matches a noun-phrase slot (1-3 tokens). */
    object NounPhrase : PatternElement()

    /** Matches any form of "be". */
    object BeVerb : PatternElement()
}

/** Possessive pronouns and contractions. */
val POSSESSIVE_FORMS = setOf(
    "my", "your", "his", "her", "its", "our", "their",
    "mine", "yours", "hers", "ours", "theirs",
    "'s",
)

/** Reflexive pronouns. */
val REFLEXIVE_FORMS = setOf(
    "myself", "yourself", "himself", "herself", "itself",
    "ourselves", "yourselves", "themselves",
)

/** Forms of "be". */
val BE_FORMS = setOf(
    "be", "am", "is", "are", "was", "were", "been", "being",
)

/**
 * Determiners that can start a noun phrase slot.
 */
val DETERMINERS = setOf(
    "a", "an", "the", "this", "that", "these", "those",
    "my", "your", "his", "her", "its", "our", "their",
    "some", "any", "no", "every", "each", "all", "both",
    "few", "many", "several", "much", "one", "two", "three",
)
