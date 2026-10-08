package com.sublearn.core.translate

import com.sublearn.core.common.AppResult

/**
 * Anything that can turn text from one language into another. The only implementation shipped now
 * is on-device Google ML Kit; the interface exists so an online engine or a different on-device
 * stack can be swapped in without touching a screen (GEN-6, LICENSE SAFETY rule 2).
 */
interface TranslationProvider {
    val id: String

    /** True when the language pair works without a network and without a download in progress. */
    suspend fun isPairAvailable(sourceLanguage: String, targetLanguage: String): Boolean

    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): AppResult<String>

    /**
     * Batch translation. ML Kit handles a list in one call, which is much faster for the "translate
     * the whole block" path and keeps punctuation context intact.
     */
    suspend fun translateAll(texts: List<String>, sourceLanguage: String, targetLanguage: String): List<AppResult<String>>

    /** Downloads the model for the pair, reporting progress. Cancellation must be honoured. */
    suspend fun downloadModel(
        sourceLanguage: String,
        targetLanguage: String,
        onProgress: (TranslationProgress) -> Unit,
    ): AppResult<Unit>

    suspend fun deleteModel(sourceLanguage: String, targetLanguage: String): AppResult<Unit>

    suspend fun downloadedPairs(): List<Pair<String, String>>
}

/** Progress of a model download, mapped from ML Kit's own statuses. */
data class TranslationProgress(
    val status: Status,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    enum class Status { PENDING, RUNNING, SUCCESS, FAILED, CANCELED, PAUSED, UNKNOWN }

    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}
