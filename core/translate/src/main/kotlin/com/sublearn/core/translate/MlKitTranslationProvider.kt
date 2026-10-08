package com.sublearn.core.translate

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslationModelManager
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.common.SubLearnLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWith

/**
 * On-device translation through the official ML Kit Translate client.
 *
 * ML Kit downloads and owns its own models, which is exactly what the licence audit requires: no
 * extracted `.bipe` packs, no reverse-engineered model loader (docs/DECISIONS.md D-014). Model
 * files live in the app's private storage and are never copied into the repository.
 */
class MlKitTranslationProvider(
    private val context: Context,
    private val logger: SubLearnLogger? = null,
) : TranslationProvider {
    override val id: String = PROVIDER_ID

    private val modelManager: TranslationModelManager
        get() = TranslationManagerHolder.get(context)

    @Volatile
    private var cachedTranslator: Translator? = null

    @Volatile
    private var cachedKey: String? = null

    override suspend fun isPairAvailable(sourceLanguage: String, targetLanguage: String): Boolean =
        runCatching { modelManager.isModelDownloaded(languageTag(sourceLanguage), languageTag(targetLanguage)) }.getOrDefault(false)

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
        val translator = runCatching { obtainTranslator(source, target) }
            .getOrElse { return texts.map { AppResult.failure(errorFrom(it, source, target)) } }
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
     * Downloads the pair's model with ML Kit's own task API.
     *
     * Progress is reported as coarse states rather than bytes: the bundled `translate` client only
     * exposes byte counts through a listener whose shape changed across ML Kit versions, and the
     * app prefers a stable indeterminate indicator over a version-fragile API (docs/KNOWN_ISSUES.md).
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
            obtainTranslator(source, target).downloadModelIfNeeded(downloadConditions()).awaitTask<Unit>()
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

    private fun downloadConditions(): DownloadConditions = DownloadConditions.Builder()
        .requireWifi()
        .build()

    override suspend fun deleteModel(sourceLanguage: String, targetLanguage: String): AppResult<Unit> =
        runCatching {
            modelManager.deleteDownloadedModel(languageTag(sourceLanguage), languageTag(targetLanguage)).awaitTask<Unit>()
            AppResult.success(Unit)
        }.getOrElse {
            if (it is CancellationException) throw it
            AppResult.failure(SubLearnError.from(it))
        }

    override suspend fun downloadedPairs(): List<Pair<String, String>> = runCatching {
        modelManager.downloadedModels.getOrEmpty().map { it.sourceLanguage to it.targetLanguage }
    }.getOrDefault(emptyList())

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

    private fun errorFrom(t: Throwable, source: String, target: String): SubLearnError = when {
        t is MlKitException && t.errorCode == MlKitException.CODE_UNAVAILABLE_MODEL -> SubLearnError(
            SubLearnError.Kind.ModelMissing,
            "translation model $source->$target is not downloaded",
            t,
        )

        t is MlKitException && (t.message?.contains("network", ignoreCase = true) == true) -> SubLearnError(
            SubLearnError.Kind.NetworkUnavailable,
            "the model needs to be fetched once before it can work offline",
            t,
        )
        t is MlKitException -> SubLearnError(SubLearnError.Kind.Unknown, t.message ?: "ML Kit error ${t.errorCode}", t)

        else -> SubLearnError.from(t)
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { t ->
            if (cont.isActive) cont.resumeWith(Result.failure(t ?: IllegalStateException("ML Kit task failed")))
        }
        addOnCanceledListener { if (cont.isActive) cont.cancel(CancellationException("translation cancelled")) }
    }

    private fun <T> Task<T>.getOrEmpty(): T? = if (isSuccessful) result else null

    companion object {
        const val PROVIDER_ID = "mlkit"
        private const val TAG = "Translate"
    }
}

/** Keeps one manager per process: ML Kit's own client is a singleton and repeated gets are wasteful. */
private object TranslationManagerHolder {
    @Volatile
    private var instance: TranslationModelManager? = null

    fun get(context: Context): TranslationModelManager = instance ?: synchronized(this) {
        instance ?: Translation.getClientManager(context.applicationContext).also { instance = it }
    }
}
