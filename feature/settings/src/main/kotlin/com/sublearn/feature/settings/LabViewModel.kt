package com.sublearn.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.ai.ProfiledNlpEngineCache
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.lexicon.PackLexiconAccess
import com.sublearn.core.models.ProcessResourceReader
import com.sublearn.core.models.ProcessResources
import com.sublearn.core.nlp.CueSlice
import com.sublearn.core.nlp.CueStreamPhraseAnalyzer
import com.sublearn.core.nlp.LexiconPosTagger
import com.sublearn.core.nlp.NlpConfig
import com.sublearn.core.nlp.PhraseSpan
import com.sublearn.core.settings.AccuracyProfileToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.SubtitleDocument
import com.sublearn.core.subtitles.SubtitleRepository
import com.sublearn.core.subtitles.Tokenizer
import com.sublearn.core.subtitles.TrackRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One phrase the analyser found in the block. */
data class LabSpanRow(
    val headword: String,
    val kind: String,
    val confidencePercent: Int,
    val text: String,
)

/** One word of the block, with what the pack and the ranker said about it. */
data class LabWordRow(
    val word: String,
    val lemmaKey: String?,
    val cefr: String?,
    val pos: String?,
    val senseCount: Int,
    /** The meaning the ranker put first, or null when the word was not ranked. */
    val topSenseFa: String?,
    val topSensePos: String?,
)

/** What the analysis of one block found and what it cost. */
data class LabBlockResult(
    val spans: List<LabSpanRow>,
    val words: List<LabWordRow>,
    val knownWords: Int,
    val unknownWords: Int,
    val rankedWords: Int,
    val phraseMs: Long,
    val lookupMs: Long,
    val rankMs: Long,
) {
    val totalMs: Long get() = phraseMs + lookupMs + rankMs
}

/** The phrase pass over the first blocks of the file, timed. */
data class LabBenchmark(
    val blocks: Int,
    val totalMs: Long,
    val slowestMs: Long,
    val spans: Int,
) {
    val perBlockMs: Float get() = if (blocks == 0) 0f else totalMs.toFloat() / blocks
}

/** What the profile engine reports about itself after an analysis. */
data class LabEngineReport(
    val meetsProfile: Boolean,
    val runtimes: List<String>,
    val diagnostics: List<String>,
    val packId: String,
    val packVersion: String,
)

/** The lab screen. */
data class LabUi(
    val fileName: String? = null,
    val format: String? = null,
    val charset: String? = null,
    val blockCount: Int = 0,
    val index: Int = 0,
    val blockText: String = "",
    val blockStartMs: Long = 0L,
    val blockEndMs: Long = 0L,
    val loading: Boolean = false,
    val analyzing: Boolean = false,
    val error: String? = null,
    val result: LabBlockResult? = null,
    val benchmark: LabBenchmark? = null,
    val engine: LabEngineReport? = null,
    val resources: ProcessResources? = null,
) {
    val hasFile: Boolean get() = blockCount > 0
    val busy: Boolean get() = loading || analyzing
}

/**
 * The subtitle lab: the player's text pipeline run on one subtitle file, with no video (D-87).
 *
 * The owner wants to try a pack or a profile and see what it does before sitting through a film
 * with it. The lab loads a subtitle the same way the player does, runs the same phrase analyser
 * over the same three-block window, looks every word up in the same pack and ranks the meanings
 * with the same profile ranker — and then shows the numbers the player never shows: what each
 * stage took and what the process is using. Nothing here is a simulation; a profile that falls
 * back in the lab falls back in the player too.
 *
 * Ranking is bounded to [RANK_LIMIT] words per block. Ranking is what a tap costs in the player
 * and the SMART profile pays for it with an LLM call, so ranking every word of every block would
 * take minutes and say nothing a tap does not.
 *
 * @property settingsRepository the accuracy profile and the phrase settings live here
 * @property subtitles parses the picked file, exactly as the player would
 * @property installedPacksRepo the installed packs, for switching between them
 * @property lexiconAccess the open lexicon
 * @property engineCache the profile's ranker and its runtime report
 * @property resources what the process is using right now
 * @property context resolves the picked file
 */
class LabViewModel(
    private val settingsRepository: SettingsRepository,
    private val subtitles: SubtitleRepository,
    private val installedPacksRepo: InstalledPackRepository,
    private val lexiconAccess: PackLexiconAccess,
    private val engineCache: ProfiledNlpEngineCache,
    private val resources: ProcessResourceReader,
    private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val installedPacks: StateFlow<List<InstalledPackEntity>> = installedPacksRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(LabUi(resources = resources.read()))
    val ui: StateFlow<LabUi> = _ui.asStateFlow()

    private val analyzer = CueStreamPhraseAnalyzer()
    private val posTagger = LexiconPosTagger()
    private var document: SubtitleDocument? = null

    /** Parse the picked subtitle file and show its first block. */
    fun loadSubtitle(uriString: String, name: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
                        ?: error("the provider returned no stream")
                    subtitles.parse(name, bytes, TrackRole.LEARNING)
                }
            }
            val track = loaded.getOrNull()?.getOrNull()
            val failure = loaded.exceptionOrNull()?.message ?: loaded.getOrNull()?.errorOrNull()?.message
            if (track == null || track.document.isEmpty()) {
                document = null
                _ui.update {
                    it.copy(
                        loading = false,
                        fileName = name,
                        blockCount = 0,
                        error = failure ?: name,
                        result = null,
                        benchmark = null,
                    )
                }
                return@launch
            }
            document = track.document
            _ui.update {
                it.copy(
                    loading = false,
                    fileName = name,
                    format = track.format.name,
                    charset = track.charsetName,
                    blockCount = track.document.blocks.size,
                    index = 0,
                    result = null,
                    benchmark = null,
                    error = null,
                )
            }
            showBlock(0)
        }
    }

    fun next() = showBlock(_ui.value.index + 1)

    fun previous() = showBlock(_ui.value.index - 1)

    /** Store the profile the user picked; the engine cache reads it on the next analysis. */
    fun setAccuracyProfile(profile: AccuracyProfileToken) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(smartLearning = it.smartLearning.copy(accuracyProfile = profile)) }
        }
    }

    /** Make one installed pack the active one and reopen the lexicon on it. */
    fun activatePack(pack: InstalledPackEntity) {
        if (pack.enabled) return
        viewModelScope.launch {
            installedPacksRepo.getAll().filter { it.kind == pack.kind && it.enabled }.forEach { other ->
                installedPacksRepo.setEnabled(other.packId, false)
            }
            installedPacksRepo.setEnabled(pack.packId, true)
            lexiconAccess.reloadFromRepository()
            _ui.update { it.copy(result = null, benchmark = null, resources = resources.read()) }
        }
    }

    fun refreshResources() {
        _ui.update { it.copy(resources = resources.read()) }
    }

    /** Run the full pipeline — phrases, lookups, ranking — on the block that is showing. */
    fun analyzeCurrent() {
        val doc = document ?: return
        val block = doc.blocks.getOrNull(_ui.value.index) ?: return
        if (_ui.value.analyzing) return
        viewModelScope.launch {
            _ui.update { it.copy(analyzing = true, error = null) }
            val outcome = withContext(Dispatchers.Default) { runCatching { analyzeBlock(doc, block) } }
            val report = engineCache.lastReport
            _ui.update {
                it.copy(
                    analyzing = false,
                    result = outcome.getOrNull(),
                    error = outcome.exceptionOrNull()?.message,
                    engine = LabEngineReport(
                        meetsProfile = report?.meetsProfile ?: false,
                        runtimes = report?.runtimeNames.orEmpty(),
                        diagnostics = report?.diagnostics.orEmpty(),
                        packId = lexiconAccess.activePackId(),
                        packVersion = lexiconAccess.activePackVersion(),
                    ),
                    resources = resources.read(),
                )
            }
        }
    }

    /** Time the phrase pass over the first [BENCHMARK_BLOCKS] blocks. */
    fun benchmark() {
        val doc = document ?: return
        if (_ui.value.analyzing) return
        viewModelScope.launch {
            _ui.update { it.copy(analyzing = true, error = null) }
            val outcome = withContext(Dispatchers.Default) { runCatching { benchmarkPhrases(doc) } }
            _ui.update {
                it.copy(
                    analyzing = false,
                    benchmark = outcome.getOrNull(),
                    error = outcome.exceptionOrNull()?.message,
                    resources = resources.read(),
                )
            }
        }
    }

    private fun showBlock(index: Int) {
        val doc = document ?: return
        val clamped = index.coerceIn(0, doc.blocks.lastIndex)
        val block = doc.blocks[clamped]
        _ui.update {
            it.copy(
                index = clamped,
                blockText = block.text,
                blockStartMs = block.startMs,
                blockEndMs = block.endMs,
                result = null,
                error = null,
            )
        }
    }

    private fun nlpConfig(): NlpConfig {
        val smart = settings.value.smartLearning
        return NlpConfig(
            phrasalVerbsEnabled = smart.phrasalVerbsEnabled,
            idiomsEnabled = smart.idiomsEnabled,
            collocationsEnabled = smart.collocationsEnabled,
            crossBlockBridgeEnabled = smart.crossBlockBridgeEnabled,
            confidenceThreshold = smart.confidenceThreshold,
            showPossibleMatches = smart.showPossibleMatches,
        )
    }

    /** The player's three-block window around [block], as cue slices. */
    private fun window(doc: SubtitleDocument, index: Int): List<CueSlice> {
        val from = (index - 1).coerceAtLeast(0)
        val to = (index + 1).coerceAtMost(doc.blocks.lastIndex)
        return doc.blocks.subList(from, to + 1).map { b ->
            CueSlice(cueId = b.id, blockId = b.id, startMs = b.startMs, endMs = b.endMs, text = b.text)
        }
    }

    private fun tagger(): LexiconPosTagger? =
        if (settings.value.smartLearning.accuracyProfile != AccuracyProfileToken.BASIC) posTagger else null

    private suspend fun phrasesFor(doc: SubtitleDocument, index: Int, config: NlpConfig): List<PhraseSpan> {
        val block = doc.blocks[index]
        return analyzer.analyze(window(doc, index), lexiconAccess, config, tagger())[block.id].orEmpty()
            .filter { it.isAboveThreshold(config.confidenceThreshold) }
    }

    private suspend fun analyzeBlock(doc: SubtitleDocument, block: SubtitleBlock): LabBlockResult {
        lexiconAccess.ensureInitialized()
        val config = nlpConfig()
        val index = doc.blocks.indexOf(block)

        val phraseStart = System.nanoTime()
        val spans = phrasesFor(doc, index, config)
        val phraseMs = elapsedMs(phraseStart)

        val lookupStart = System.nanoTime()
        val words = Tokenizer.spans(block.text).filter { it.isWord && it.text.any(Char::isLetter) }
        val lookedUp = words.map { span ->
            val word = span.text.lowercase()
            val lemmas = lexiconAccess.lemmatiser(word)
            val keys = (listOf(word) + lemmas).distinct()
            val entry = keys.firstNotNullOfOrNull { lexiconAccess.lookupWord(it) }
            val senses = entry?.let { lexiconAccess.senses(it.id) }.orEmpty()
            Triple(span.text, entry, senses)
        }
        val lookupMs = elapsedMs(lookupStart)

        val rankStart = System.nanoTime()
        val ranker = engineCache.senseRanker()
        val context = block.text.split(CONTEXT_SEPARATOR).filter { it.isNotBlank() }
        var ranked = 0
        val rows = lookedUp.map { (text, entry, senses) ->
            val top = if (entry != null && senses.size > 1 && ranked < RANK_LIMIT) {
                ranked++
                ranker.rank(entry.key, senses, context, entry.pos).firstOrNull()
            } else {
                null
            }
            LabWordRow(
                word = text,
                lemmaKey = entry?.key,
                cefr = entry?.cefr,
                pos = entry?.pos,
                senseCount = senses.size,
                topSenseFa = top?.glossFa,
                topSensePos = top?.pos,
            )
        }
        val rankMs = elapsedMs(rankStart)

        return LabBlockResult(
            spans = spans.map { span ->
                LabSpanRow(
                    headword = span.headword,
                    kind = span.kind.name,
                    confidencePercent = (span.confidence * 100).toInt().coerceIn(0, 100),
                    text = block.text.substring(
                        span.startChar.coerceIn(0, block.text.length),
                        span.endChar.coerceIn(0, block.text.length),
                    ),
                )
            },
            words = rows,
            knownWords = rows.count { it.lemmaKey != null },
            unknownWords = rows.count { it.lemmaKey == null },
            rankedWords = ranked,
            phraseMs = phraseMs,
            lookupMs = lookupMs,
            rankMs = rankMs,
        )
    }

    private suspend fun benchmarkPhrases(doc: SubtitleDocument): LabBenchmark {
        lexiconAccess.ensureInitialized()
        val config = nlpConfig()
        val count = doc.blocks.size.coerceAtMost(BENCHMARK_BLOCKS)
        var slowest = 0L
        var spans = 0
        val start = System.nanoTime()
        for (index in 0 until count) {
            val blockStart = System.nanoTime()
            spans += phrasesFor(doc, index, config).size
            slowest = maxOf(slowest, elapsedMs(blockStart))
        }
        return LabBenchmark(blocks = count, totalMs = elapsedMs(start), slowestMs = slowest, spans = spans)
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    companion object {
        /** How many words of a block are ranked; the rest show their sense count only. */
        const val RANK_LIMIT = 8

        /** How many blocks the benchmark runs over. */
        const val BENCHMARK_BLOCKS = 200

        private val CONTEXT_SEPARATOR = Regex("\\s+")
    }
}
