package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is known about the offline translation model of the current language pair.
 *
 * One vocabulary for the whole app: the settings screen, the downloads screen and the player all
 * read the same state, so the three places cannot disagree about whether the model is on the
 * device (D-81).
 */
sealed interface TranslationModelState {
    /** Nobody has asked yet. The UI shows "unknown" rather than claiming the model is missing. */
    data object Unknown : TranslationModelState

    /** The pair works offline right now. */
    data object Installed : TranslationModelState

    /** The model is not on the device yet; a download brings it. */
    data object NotInstalled : TranslationModelState

    /** A download is running. */
    data object Downloading : TranslationModelState

    /** The last attempt failed; [message] says why. */
    data class Failed(val message: String) : TranslationModelState

    /** True when a download is worth offering. */
    val needsDownload: Boolean get() = this is NotInstalled || this is Failed
}

/**
 * The one place that knows how the offline translation model is fetched, checked and removed.
 *
 * The model belongs to ML Kit: the SDK downloads it, verifies it, stores it where it expects it
 * and deletes it again. The app never unpacks a model archive itself, because a model that sits
 * in the wrong directory is a model the SDK does not see — which is why the previous hand-written
 * installer could report "installed" and still leave the app unable to translate (D-14, D-81).
 *
 * Every screen that offers the download goes through this object, so the download the player
 * starts and the one the settings screen starts are the same download, with the same state.
 *
 * @property provider the engine that owns the model
 * @property pair the language pair to act on, read from the settings each time it is needed
 */
class TranslationModelManager(
    private val provider: TranslationProvider,
    private val pair: () -> Pair<String, String>,
) {
    private val _state = MutableStateFlow<TranslationModelState>(TranslationModelState.Unknown)
    val state: StateFlow<TranslationModelState> = _state.asStateFlow()

    /** The pair this manager acts on right now. */
    fun currentPair(): Pair<String, String> = pair()

    /**
     * Ask the engine whether the model is on the device, and remember the answer.
     *
     * Never throws: a failing lookup is "not installed", because that is what the user can act on.
     */
    suspend fun refresh(): TranslationModelState {
        val (source, target) = pair()
        val available = runCatching { provider.isPairAvailable(source, target) }.getOrDefault(false)
        val next = if (available) TranslationModelState.Installed else TranslationModelState.NotInstalled
        _state.value = next
        return next
    }

    /**
     * Download the model of the pair.
     *
     * The progress callback receives a fraction between 0 and 1, or null when the engine cannot
     * report one — ML Kit's own API only says "started" and "finished", so the bar is
     * indeterminate rather than a number that would be a lie (docs/KNOWN_ISSUES.md).
     */
    suspend fun download(onProgress: (Float?) -> Unit = {}): AppResult<Unit> {
        val (source, target) = pair()
        _state.value = TranslationModelState.Downloading
        val result = provider.downloadModel(source, target) { progress ->
            when (progress.status) {
                TranslationProgress.Status.PENDING,
                TranslationProgress.Status.RUNNING,
                -> onProgress(progress.fraction.takeIf { it > 0f })

                TranslationProgress.Status.SUCCESS -> onProgress(1f)
                else -> Unit
            }
        }
        _state.value = when (result) {
            is AppResult.Success -> TranslationModelState.Installed
            is AppResult.Failure -> TranslationModelState.Failed(
                result.error.message ?: "the translation model could not be downloaded",
            )
        }
        return result
    }

    /** Remove the model of the pair from the device. */
    suspend fun remove(): AppResult<Unit> {
        val (source, target) = pair()
        val result = provider.deleteModel(source, target)
        _state.value = when (result) {
            is AppResult.Success -> TranslationModelState.NotInstalled
            is AppResult.Failure -> TranslationModelState.Failed(
                result.error.message ?: "the translation model could not be removed",
            )
        }
        return result
    }

    /** True when the pair can be translated right now, without a download. */
    suspend fun isReady(): Boolean {
        val (source, target) = pair()
        return runCatching { provider.isPairAvailable(source, target) }.getOrDefault(false)
    }

    /**
     * How large the download of the current pair is, when the size is known.
     *
     * The English–Persian package is published by Google with a fixed size; a pair whose size is
     * not known returns null, and the card then shows no size instead of a made-up one.
     */
    fun downloadSizeBytes(): Long? {
        val (source, target) = pair()
        val languages = setOf(source.trim().lowercase(), target.trim().lowercase())
        return when {
            languages == setOf("en", "fa") -> EN_FA_DOWNLOAD_BYTES
            else -> null
        }
    }

    companion object {
        /** The published size of the English–Persian translation package, in bytes. */
        const val EN_FA_DOWNLOAD_BYTES = 32_405_428L
    }
}
