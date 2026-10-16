package com.sublearn.core.ai

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import java.io.File

/**
 * A loaded language model that can answer a prompt.
 *
 * `core:ai` declares this so the SMART profile's analysis code does not have to know about
 * MediaPipe; `core:nlp-llm`'s `LiteRtLlmRunner` implements it (`D-75`).
 */
interface LlmRunner : AutoCloseable {
    /** The engine that answered, for the diagnostics screen. */
    val runtimeName: String

    /** The catalog id of the bundle in memory. */
    val modelId: String

    /** Answers one prompt. Blocking: the caller owns the coroutine and the dispatcher. */
    fun generate(prompt: String): AppResult<String>

    /** Picks one of [options], or `Success(null)` when the model's answer matches none. */
    fun choose(prompt: String, options: List<String>): AppResult<Int?>
}

/**
 * Opens one model's embedder. Implemented per engine: `core:nlp-onnx` supplies the ONNX one and
 * `core:ai` itself supplies the pure-Kotlin one, so the selection below stays engine-agnostic.
 */
fun interface EmbedderLoader {
    fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<TextEmbedder>
}

/** Opens one model's LLM. Implemented by `core:nlp-llm` for the LiteRT bundles. */
fun interface LlmLoader {
    fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<LlmRunner>
}

/**
 * What a [ProfiledNlpEngine] managed to open, and whether that is good enough for the profile.
 *
 * @property meetsProfile true when every model the profile's *runtime* needs was opened
 * @property embedder the sentence embedder in use, or null when the profile has none
 * @property llm the prompt model in use, or null when the profile has none
 * @property diagnostics one line per model considered, as `"<model id>: <what happened>"`
 */
data class ProfileRuntimeReport(
    val meetsProfile: Boolean,
    val embedder: TextEmbedder?,
    val llm: LlmRunner?,
    val diagnostics: List<String>,
) {
    /** The engine names in use, for the settings diagnostics line. */
    val runtimeNames: List<String>
        get() = listOfNotNull(embedder?.runtimeName, llm?.runtimeName).distinct()

    /** True when nothing at all could be opened, so the caller should use lexical ranking. */
    val isEmpty: Boolean get() = embedder == null && llm == null
}

/**
 * Loads the models one accuracy profile needs, strongest engine first (`D-74`).
 *
 * The profile is a promise about quality, so the caller must ask before believing it: this class
 * opens whatever is installed and reports, in [ProfileRuntimeReport.meetsProfile], whether that
 * matches what the profile needs. It never *claims* the profile is satisfied by a weaker model —
 * a PRECISE device with only the BALANCED model installed gets a report of `false` and the weaker
 * embedder, so the UI can say which profile is actually running.
 *
 * Everything the engines need arrives as an [EmbedderLoader] / [LlmLoader], which is what keeps
 * this class in a pure-JVM module: a unit test supplies fakes, and the app supplies the ONNX and
 * LiteRT implementations.
 *
 * @property root the models directory, as [ModelStore] understands it
 * @property profile the tier the user selected or [SmartProfileSelector] picked
 * @property installedModelIds catalog ids present on disk
 */
class ProfiledNlpEngine(
    private val root: File,
    private val profile: ProfileTier,
    private val installedModelIds: Set<String>,
    private val embedderLoader: EmbedderLoader? = null,
    private val llmLoader: LlmLoader? = null,
) : AutoCloseable {

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val loaded = ArrayList<AutoCloseable>(3)
    private val notes = ArrayList<String>(6)
    private val openedIds = LinkedHashSet<String>()

    /** Whether the profile's runtime models were all opened by the last [load]. */
    var meetsProfile: Boolean = false
        private set

    /**
     * Opens the models the profile's runtime needs.
     *
     * A model that will not open is recorded in the diagnostics and skipped; the next one is tried,
     * so a device with a partially installed profile still gets usable ranking. The report tells the
     * caller exactly how far it got.
     *
     * The caller owns the returned handles and must [close] this engine when done.
     */
    fun load(): AppResult<ProfileRuntimeReport> {
        if (closed.get()) return failure("the engine is closed")

        for (model in NlpModelCatalog.runtimeModelsFor(profile)) {
            if (model.id in openedIds) continue

            if (model.id !in installedModelIds) {
                note(model.id, "not installed")
                continue
            }
            val layout = ModelStore.LAYOUTS[model.id]
            if (layout == null) {
                note(model.id, "this build has no file layout for it")
                continue
            }
            if (!model.runtime.isAvailable) {
                note(model.id, "the ${model.runtime.name} engine is not linked into this build")
                continue
            }

            when (model.runtime) {
                NlpModelRuntime.LITERT -> openLlm(model, layout)
                NlpModelRuntime.ONNX -> openOnnx(model, layout)
                NlpModelRuntime.NONE -> openStatic(model, layout)
            }
        }

        val needed = NlpModelCatalog.runtimeModelsFor(profile).map { it.id }
        meetsProfile = needed.all { it in openedIds }

        return AppResult.Success(
            ProfileRuntimeReport(
                meetsProfile = meetsProfile,
                embedder = embedder,
                llm = llm,
                diagnostics = notes.toList(),
            ),
        )
    }

    /** The strongest embedder that opened, or null when this profile has none. */
    var embedder: TextEmbedder? = null
        private set

    /** The profile's prompt model, or null when it has none or it would not open. */
    var llm: LlmRunner? = null
        private set

    private fun openLlm(model: NlpModel, layout: ModelFileLayout) {
        val loader = llmLoader ?: run {
            note(model.id, "no LLM loader was supplied")
            return
        }
        when (val result = loader.load(model, layout, root)) {
            is AppResult.Success -> {
                llm = result.value
                loaded += result.value
                openedIds += model.id
                note(model.id, "loaded with ${result.value.runtimeName}")
            }

            is AppResult.Failure -> note(model.id, result.error.message)
        }
    }

    private fun openOnnx(model: NlpModel, layout: ModelFileLayout) {
        if (embedder != null) {
            // One sentence encoder is enough: loading a second would double the resident memory
            // for the same signal, and the profile list is ordered strongest-first.
            note(model.id, "skipped, ${embedder?.runtimeName} is already loaded")
            return
        }
        val loader = embedderLoader ?: run {
            note(model.id, "no ONNX loader was supplied")
            return
        }
        when (val result = loader.load(model, layout, root)) {
            is AppResult.Success -> {
                embedder = result.value
                loaded += result.value
                openedIds += model.id
                note(model.id, "loaded with ${result.value.runtimeName}, ${result.value.dimensions} dims")
            }

            is AppResult.Failure -> note(model.id, result.error.message)
        }
    }

    private fun openStatic(model: NlpModel, layout: ModelFileLayout) {
        if (embedder != null) {
            note(model.id, "skipped, ${embedder?.runtimeName} is already loaded")
            return
        }
        val sessionFile = layout.sessionFile
        if (sessionFile == null) {
            note(model.id, "the layout has no primary file")
            return
        }
        when (val result = StaticTokenEmbedder.load(layout.directoryUnder(root), sessionFile)) {
            is AppResult.Success -> {
                embedder = result.value
                loaded += result.value
                openedIds += model.id
                note(model.id, "loaded with ${result.value.runtimeName}, ${result.value.dimensions} dims")
            }

            is AppResult.Failure -> note(model.id, result.error.message)
        }
    }

    private fun note(modelId: String, message: String) {
        notes += "$modelId: $message"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (resource in loaded) runCatching { resource.close() }
        loaded.clear()
        openedIds.clear()
        embedder = null
        llm = null
    }

    private fun failure(message: String): AppResult.Failure = AppResult.Failure(
        SubLearnError(SubLearnError.Kind.ModelFailure, message),
    )
}

/**
 * The pure-Kotlin loader: `potion-base-8M` is a safetensors lookup table, so BALANCED needs no
 * native library at all and works on every device that can hold the file.
 */
object StaticEmbedderLoader : EmbedderLoader {
    override fun load(model: NlpModel, layout: ModelFileLayout, root: File): AppResult<TextEmbedder> =
        StaticTokenEmbedder.load(layout.directoryUnder(root), layout.sessionFile ?: ModelStore.VOCAB_FILE)
}
