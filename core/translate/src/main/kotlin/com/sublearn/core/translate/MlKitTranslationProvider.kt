package com.sublearn.core.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.common.SubLearnLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation through the official ML Kit Translate client.
 *
 * ML Kit downloads and owns its own models, so the app never handles a model file itself: no
 * extracted `.bipe` packs, no custom model loader (docs/DECISIONS.md D-14). Model files live in the
 * app's private storage and are never copied into the repository.
 *
 * ML Kit stores one model per language (English is the pivot and ships with the SDK), so a "pair"
 * here is available when both of its language models are on the device.
 */
class MlKitTranslationProvider(
    private val logger: SubLearnLogger? = null,
) : TranslationProvider {
    override val id: String = PROVIDER_ID

    private val modelManager: RemoteModelManager
        get() = RemoteModelManager.getInstance()

    @Volatile
    private var cachedTranslator: Translator? = null

    @Volatile
    private var cachedKey: String? = null

    override suspend fun isPairAvailable(sourceLanguage: String, targetLanguage: String): Boolean {
        val source = languageTag(sourceLanguage)
        val target = languageTag(targetLanguage)
        return isModelDownloaded(source) && isModelDownloaded(target)
    }

    private suspend fun isModelDownloaded(language: String): Boolean {
        if (language == TranslateLanguage.ENGLISH) return true
        return try {
            modelManager.isModelDownloaded(remoteModel(language)).awaitTask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logger?.log(SubLearnLogger.Level.WARN, TAG, "model lookup failed for $language: ${t.message}")
            false
        }
    }

    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): AppResult<String> {
        if (text.isBlank()) return AppResult.success("")
        return translateAll(listOf(text), sourceLanguage, targetLanguage).first()
    }

    override suspend fun translateAll(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<AppResult<String>> {
        if (texts.isEmpty()) return emptyList()
        val source = languageTag(sourceLanguage)
        val target = languageTag(targetLanguage)
        val translator = try {
            obtainTranslator(source, target)
        } catch (t: Throwable) {
            return texts.map { AppResult.failure(errorFrom(t, source, target)) }
        }
        return texts.map { text ->
            try {
                AppResult.success(translator.translate(text).awaitTask())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppResult.failure(errorFrom(t, source, target))
            }
        }
    }

    private fun obtainTranslator(source: String, target: String): Translator {
        val key = "$source->$target"
        cachedTranslator?.let { if (cachedKey == key) return it }
        synchronized(this) {
            cachedTranslator?.let { if (cachedKey == key) return it }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            return Translation.getClient(options).also {
                cachedTranslator?.close()
                cachedTranslator = it
                cachedKey = key
            }
        }
    }

    /**
     * Downloads the pair's models with ML Kit's own task API.
     *
     * ML Kit only ever downloads the *target* language of a translator, so the language that is
     * actually missing is fetched with a translator that has it as its target. English is part of
     * the SDK and is never downloaded.
     *
     * Progress is reported as coarse states rather than bytes: the `translate` client exposes no
     * byte counts, and the app prefers a stable indeterminate indicator (docs/KNOWN_ISSUES.md).
     */
    override suspend fun downloadModel(
        sourceLanguage: String,
        targetLanguage: String,
        onProgress: (TranslationProgress) -> Unit,
    ): AppResult<Unit> {
        val source = languageTag(sourceLanguage)
        val target = languageTag(targetLanguage)
        if (isPairAvailable(sourceLanguage, targetLanguage)) {
            onProgress(TranslationProgress(TranslationProgress.Status.SUCCESS))
            return AppResult.success(Unit)
        }
        onProgress(TranslationProgress(TranslationProgress.Status.PENDING))
        return try {
            for (language in missingLanguages(source, target)) {
                downloadLanguage(language)
            }
            onProgress(TranslationProgress(TranslationProgress.Status.SUCCESS))
            AppResult.success(Unit)
        } catch (cancelled: CancellationException) {
            onProgress(TranslationProgress(TranslationProgress.Status.CANCELED))
            throw cancelled
        } catch (t: Throwable) {
            logger?.log(SubLearnLogger.Level.WARN, TAG, "model download failed for $source->$target: ${t.message}")
            onProgress(TranslationProgress(TranslationProgress.Status.FAILED))
            AppResult.failure(errorFrom(t, source, target))
        }
    }

    /** The languages of the pair that the SDK does not ship and that are not on the device. */
    private suspend fun missingLanguages(source: String, target: String): List<String> =
        listOf(source, target)
            .distinct()
            .filter { it != TranslateLanguage.ENGLISH }
            .filter { !isModelDownloaded(it) }

    /** Fetches one language model through a translator that has it as its target. */
    private suspend fun downloadLanguage(language: String) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(language)
            .build()
        Translation.getClient(options).downloadModelIfNeeded(downloadConditions()).awaitTask()
    }

    /**
     * The conditions the download runs under.
     *
     * No Wi-Fi requirement: the user asked for the model, the download is about 30 MB and it
     * happens once. Requiring Wi-Fi made the button look broken on a phone that was online over
     * mobile data, which is the common case (D-81).
     */
    private fun downloadConditions(): DownloadConditions = DownloadConditions.Builder().build()

    /** Removes the non-English model(s) of the pair; the English pivot is part of the SDK. */
    override suspend fun deleteModel(sourceLanguage: String, targetLanguage: String): AppResult<Unit> {
        val languages = listOf(languageTag(sourceLanguage), languageTag(targetLanguage))
            .distinct()
            .filter { it != TranslateLanguage.ENGLISH }
        return try {
            synchronized(this) {
                cachedTranslator?.close()
                cachedTranslator = null
                cachedKey = null
            }
            for (language in languages) modelManager.deleteDownloadedModel(remoteModel(language)).awaitTask()
            AppResult.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppResult.failure(SubLearnError.from(t))
        }
    }

    /** Every downloaded language paired with the English pivot, in both directions. */
    override suspend fun downloadedPairs(): List<Pair<String, String>> {
        val downloaded = try {
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            logger?.log(SubLearnLogger.Level.WARN, TAG, "listing downloaded models failed: ${t.message}")
            return emptyList()
        }
        return downloaded.map { it.language }
            .filter { it != TranslateLanguage.ENGLISH }
            .sorted()
            .flatMap { language -> listOf(TranslateLanguage.ENGLISH to language, language to TranslateLanguage.ENGLISH) }
    }

    private fun remoteModel(language: String): TranslateRemoteModel = TranslateRemoteModel.Builder(language).build()

    /** BCP-47 in, ML Kit code out. Unknown codes pass through so a user list still works. */
    internal fun languageTag(tag: String): String {
        val normalized = tag.trim().lowercase().replace('_', '-')
        TranslateLanguage.fromLanguageTag(normalized)?.let { return it }
        TranslateLanguage.fromLanguageTag(normalized.substringBefore('-'))?.let { return it }
        return when (normalized) {
            "fa", "pes", "per", "farsi", "persian" -> TranslateLanguage.PERSIAN
            "en", "eng", "english" -> TranslateLanguage.ENGLISH
            "ar", "ara" -> TranslateLanguage.ARABIC
            "tr", "tur" -> TranslateLanguage.TURKISH
            "id", "ind" -> TranslateLanguage.INDONESIAN
            "vi", "vie" -> TranslateLanguage.VIETNAMESE
            "zh", "zho", "chi", "cmn" -> TranslateLanguage.CHINESE
            "ja", "jpn" -> TranslateLanguage.JAPANESE
            "ko", "kor" -> TranslateLanguage.KOREAN
            "ru", "rus" -> TranslateLanguage.RUSSIAN
            "de", "deu", "ger" -> TranslateLanguage.GERMAN
            "fr", "fra", "fre" -> TranslateLanguage.FRENCH
            "es", "spa" -> TranslateLanguage.SPANISH
            "pt", "por" -> TranslateLanguage.PORTUGUESE
            "it", "ita" -> TranslateLanguage.ITALIAN
            "hi", "hin" -> TranslateLanguage.HINDI
            "th", "tha" -> TranslateLanguage.THAI
            "uk", "ukr" -> TranslateLanguage.UKRAINIAN
            else -> normalized
        }
    }

    private fun errorFrom(t: Throwable, source: String, target: String): SubLearnError {
        if (t !is MlKitException) return SubLearnError.from(t)
        val message = t.message.orEmpty()
        return when {
            t.errorCode == MlKitException.NETWORK_ISSUE -> SubLearnError(
                SubLearnError.Kind.NetworkUnavailable,
                "the model could not be fetched; check the connection and try again",
                t,
            )

            t.errorCode == MlKitException.NOT_FOUND ||
                t.errorCode == MlKitException.UNAVAILABLE ||
                message.contains("download", ignoreCase = true) ||
                message.contains("model", ignoreCase = true) -> SubLearnError(
                SubLearnError.Kind.ModelMissing,
                "the translation model for $source → $target is not on the device; " +
                    "download it in Settings → Downloads",
                t,
            )

            t.errorCode == MlKitException.NOT_ENOUGH_SPACE -> SubLearnError(
                SubLearnError.Kind.Unknown,
                "not enough free space for the translation model",
                t,
            )

            else -> SubLearnError(SubLearnError.Kind.Unknown, message.ifBlank { "ML Kit error ${t.errorCode}" }, t)
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val failure = task.exception
            when {
                failure != null -> if (cont.isActive) cont.resumeWithException(failure)
                task.isCanceled -> cont.cancel(CancellationException("ML Kit task cancelled"))
                else -> if (cont.isActive) cont.resume(task.result)
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "mlkit"
        private const val TAG = "Translate"
    }
}
