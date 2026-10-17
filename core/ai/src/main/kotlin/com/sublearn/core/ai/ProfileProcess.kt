package com.sublearn.core.ai

/**
 * One stage a subtitle line (or a tapped word) goes through when a profile analyses it (D-92).
 *
 * The stages are the same in the player and in the subtitle lab, because both call the same
 * analyser and the same ranker. The settings screen names each one and says which model, if any,
 * it uses, so a profile is no longer a label with a list of downloads behind it.
 */
enum class ProfileStage {
    /** Phrasal verbs, idioms and collocations are matched against the lexicon pack's patterns. No model. */
    PHRASES,

    /** Each word is looked up in the pack, through its lemma. No model. */
    LOOKUP,

    /** Part-of-speech tags from the pack's frequency table, so a phrase match has to agree with the grammar. */
    TAGGING,

    /** Meanings are kept in the pack's own order: the primary meaning first, then the part-of-speech match. No model. */
    RANK_PRIOR,

    /** A sentence encoder embeds the line and each meaning's English gloss, and the closest meaning comes first. */
    RANK_ENCODER,

    /** A prompt model chooses one meaning from the best four candidates. Falls back to the encoder order. */
    RANK_LLM,
}

/**
 * One stage of a profile, with the model that performs it when there is one.
 *
 * @property stage what the stage does
 * @property model the model it runs, or null for a stage that needs none
 */
data class ProfileStep(val stage: ProfileStage, val model: NlpModel? = null)

/**
 * The analysis a profile performs, as data (D-92).
 *
 * Mirrors the wiring in [ProfiledSenseRanker.forProfile] and the tagger choice in the player and the
 * lab. It reads the same tables as the engine ([NlpModelCatalog.runtimeModelsFor]), so when the
 * engine changes the description changes with it instead of drifting into prose.
 */
object ProfileProcess {

    /** The steps a line goes through under [profile], in the order the analyser runs them. */
    fun stepsFor(profile: ProfileTier): List<ProfileStep> {
        val steps = ArrayList<ProfileStep>(5)
        steps += ProfileStep(ProfileStage.PHRASES)
        steps += ProfileStep(ProfileStage.LOOKUP)
        // Every profile above Basic tags parts of speech; Basic is the only one without a tagger.
        if (profile != ProfileTier.BASIC) steps += ProfileStep(ProfileStage.TAGGING)

        val runtimeModels = NlpModelCatalog.runtimeModelsFor(profile)
        val encoder = runtimeModels.firstOrNull { it.runtime != NlpModelRuntime.LITERT }
        val promptModel = runtimeModels.firstOrNull { it.runtime == NlpModelRuntime.LITERT }
        when (profile) {
            ProfileTier.BASIC, ProfileTier.LIGHT, ProfileTier.CUSTOM -> steps += ProfileStep(ProfileStage.RANK_PRIOR)
            ProfileTier.BALANCED, ProfileTier.PRECISE, ProfileTier.ADVANCED ->
                steps += ProfileStep(ProfileStage.RANK_ENCODER, encoder)

            ProfileTier.SMART -> {
                steps += ProfileStep(ProfileStage.RANK_ENCODER, encoder)
                steps += ProfileStep(ProfileStage.RANK_LLM, promptModel)
            }
        }
        return steps
    }
}
