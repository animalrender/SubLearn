package com.sublearn.core.nlp

import java.security.MessageDigest

/**
 * A single subtitle cue or block slice in timeline order (D-45).
 *
 * @property cueId unique identifier for this cue within the subtitle file
 * @property blockId the display block this cue belongs to (may span multiple cues)
 * @property startMs start time in milliseconds
 * @property endMs end time in milliseconds
 * @property text raw text of the cue (before any cleanup)
 */
data class CueSlice(
    val cueId: Long,
    val blockId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Maps a CueSlice-based span back to display-block coordinates.
 * Used by the UI layer to translate analysis offsets to block/line offsets.
 */
data class CueOffsetMapping(
    val cueId: Long,
    val cueStartChar: Int,
    val cueEndChar: Int,
    val displayBlockId: Int,
    val displayStartChar: Int,
    val displayEndChar: Int,
)

/**
 * Analysis cache key. Binds results to the exact analysis input so that
 * changing pack/profile/normalizer invalidates the cache automatically.
 */
data class AnalysisCacheKey(
    val sourceKey: String,
    val contentHash: String,
    val normalizerHash: String,
    val packId: String,
    val packVersion: String,
    val analyzerVersion: Int,
    val profile: String,
) {
    fun toCacheKey(): String {
        val raw = "$sourceKey|$contentHash|$normalizerHash|$packId|$packVersion|$analyzerVersion|$profile"
        return sha256(raw)
    }

    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Configuration for sentence segmentation. */
data class SegmentConfig(
    val maxTokensPerSegment: Int = 64,
    val maxSpanTokens: Int = 12,
    val maxSpanCues: Int = 3,
    val maxSpanDurationMs: Long = 4000L,
    val hardGapMs: Long = 1500L,
    val softOverlapTokens: Int = 8,
)

/**
 * Text cleanup for analysis input.
 * Strips speaker labels, stage directions, music symbols, and normalizes quotes.
 */
object AnalysisTextCleanup {

    private val SPEAKER_LABEL = Regex("^[A-Z][A-Z ]*:\\s*", RegexOption.MULTILINE)
    private val STAGE_DIRECTION_SQ = Regex("\\[[^\\]]*\\]")
    private val STAGE_DIRECTION_RND = Regex("\\([^)]*\\.\\.\\.[^)]*\\)")
    private val MUSIC_SYMBOL = Regex("[♪♫♬\uD83C\uDFB5\uD83C\uDFB6]")
    private val LEADING_HYPHEN = Regex("^\\s*[–—-]\\s*", RegexOption.MULTILINE)
    private val HTML_TAGS = Regex("<[^>]+>")

    private val CONTRACTIONS = mapOf(
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "gimme" to "give me",
        "lemme" to "let me",
        "kinda" to "kind of",
        "'em" to "them",
    )

    fun clean(text: String): String {
        var c = text
        c = HTML_TAGS.replace(c, " ")
        c = SPEAKER_LABEL.replace(c, " ")
        c = STAGE_DIRECTION_SQ.replace(c, " ")
        c = STAGE_DIRECTION_RND.replace(c, " ")
        c = MUSIC_SYMBOL.replace(c, " ")
        c = LEADING_HYPHEN.replace(c, " ")
        c = c.replace('\u2019', '\'').replace('\u2018', '\'')
        c = c.replace('\u201C', '"').replace('\u201D', '"')
        c = c.replace(Regex("\\s+"), " ").trim()
        return c
    }

    fun isContinuationStart(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("...") || t.startsWith("\u2026")
    }

    fun isContinuationEnd(text: String): Boolean {
        val t = text.trimEnd()
        return t.endsWith("...") || t.endsWith("\u2026")
    }
}

/**
 * Continuous cue-stream phrase analyzer (D-31 revised, D-45).
 *
 * Operates on raw cue streams with text cleanup, segment limits,
 * and analysis cache keyed by content hash.
 */
class CueStreamPhraseAnalyzer(
    private val detector: PhraseDetector = DeterministicPhraseDetector(),
) {

    suspend fun analyze(
        cues: List<CueSlice>,
        lexicon: LexiconAccess,
        config: NlpConfig = NlpConfig.DEFAULT,
        posTagger: PosTagger? = null,
        segmentConfig: SegmentConfig = SegmentConfig(),
    ): Map<Long, List<PhraseSpan>> {
        if (cues.isEmpty()) return emptyMap()

        val resultByBlock = LinkedHashMap<Long, MutableList<PhraseSpan>>()
        for (cue in cues) {
            resultByBlock.getOrPut(cue.blockId) { ArrayList() }
        }

        // Clean text for analysis
        val cleanedCues = cues.map { cue ->
            CleanedCue(
                cueId = cue.cueId,
                blockId = cue.blockId,
                startMs = cue.startMs,
                endMs = cue.endMs,
                rawText = cue.text,
                cleanedText = AnalysisTextCleanup.clean(cue.text),
            )
        }

        val streams = buildStreams(cleanedCues, segmentConfig)

        for (stream in streams) {
            analyzeStream(stream, lexicon, config, posTagger, segmentConfig, resultByBlock)
        }

        return resultByBlock.mapValues { (_, list) -> list.sortedBy { it.startChar } }
    }

    private fun buildStreams(
        cues: List<CleanedCue>,
        segmentConfig: SegmentConfig,
    ): List<List<CleanedCue>> {
        val streams = ArrayList<List<CleanedCue>>()
        var currentStream = ArrayList<CleanedCue>()

        for (cue in cues) {
            if (cue.cleanedText.isBlank()) continue
            if (currentStream.isEmpty()) {
                currentStream.add(cue)
            } else {
                val prev = currentStream.last()
                val gap = cue.startMs - prev.endMs
                val canBridge = gap <= segmentConfig.hardGapMs &&
                    !endsWithSentenceBoundary(prev.cleanedText) &&
                    !AnalysisTextCleanup.isContinuationEnd(prev.rawText) &&
                    !isHardBoundaryStart(cue.rawText)
                if (canBridge) {
                    currentStream.add(cue)
                } else {
                    streams.add(currentStream)
                    currentStream = ArrayList()
                    currentStream.add(cue)
                }
            }
        }
        if (currentStream.isNotEmpty()) streams.add(currentStream)
        return streams
    }

    private suspend fun analyzeStream(
        stream: List<CleanedCue>,
        lexicon: LexiconAccess,
        config: NlpConfig,
        posTagger: PosTagger?,
        segmentConfig: SegmentConfig,
        resultByBlock: LinkedHashMap<Long, MutableList<PhraseSpan>>,
    ) {
        val mappedTokens = ArrayList<StreamToken>()
        var virtualOffset = 0

        for (cue in stream) {
            val localTokens = tokenizeText(cue.cleanedText)
            for (lt in localTokens) {
                mappedTokens += StreamToken(
                    streamToken = TokenInfo(mappedTokens.size, lt.text, virtualOffset + lt.startChar, virtualOffset + lt.endChar),
                    cueId = cue.cueId,
                    blockId = cue.blockId,
                    localTokenIndex = lt.index,
                    localStartChar = lt.startChar,
                    localEndChar = lt.endChar,
                )
            }
            virtualOffset += cue.cleanedText.length + 1
        }

        if (mappedTokens.isEmpty()) return

        val segments = segmentTokens(mappedTokens, segmentConfig)

        for (segment in segments) {
            val rawSpans = detector.detect(
                tokens = segment.map { it.streamToken },
                lexicon = lexicon,
                config = config,
                posTagger = posTagger,
            )

            for (span in rawSpans) {
                val firstIdx = span.tokens.minOrNull() ?: continue
                val lastIdx = span.tokens.maxOrNull() ?: continue
                if (firstIdx < 0 || lastIdx >= segment.size) continue

                val covered = (firstIdx..lastIdx).map { segment[it] }
                val byBlock = covered.groupBy { it.blockId }

                val allParts = byBlock.entries.map { (bId, toks) ->
                    SpanPart(bId.toInt(), toks.minOf { it.localStartChar }, toks.maxOf { it.localEndChar })
                }

                for ((bId, toksInBlock) in byBlock) {
                    val localStart = toksInBlock.minOf { it.localStartChar }
                    val localEnd = toksInBlock.maxOf { it.localEndChar }
                    val matchedSet = span.tokens.toSet()
                    val highlighted = toksInBlock.filter { it.streamToken.index in matchedSet }
                    val localIdxs = (highlighted.ifEmpty { toksInBlock }).map { it.localTokenIndex }

                    resultByBlock.getOrPut(bId) { ArrayList() }.add(
                        PhraseSpan(localStart, localEnd, localIdxs, span.kind, span.headword, span.lemmaKey,
                            span.confidence, span.separable, allParts),
                    )
                }
            }
        }
    }

    private fun segmentTokens(tokens: List<StreamToken>, config: SegmentConfig): List<List<StreamToken>> {
        if (tokens.size <= config.maxTokensPerSegment) return listOf(tokens)
        val segments = ArrayList<List<StreamToken>>()
        var start = 0
        while (start < tokens.size) {
            val end = (start + config.maxTokensPerSegment).coerceAtMost(tokens.size)
            segments.add(tokens.subList(start, end))
            start = (end - config.softOverlapTokens).coerceAtLeast(start + 1)
        }
        return segments
    }

    private data class CleanedCue(
        val cueId: Long, val blockId: Long, val startMs: Long, val endMs: Long,
        val rawText: String, val cleanedText: String,
    )

    private data class StreamToken(
        val streamToken: TokenInfo, val cueId: Long, val blockId: Long,
        val localTokenIndex: Int, val localStartChar: Int, val localEndChar: Int,
    )

    companion object {
        private val TOKEN_REGEX = Regex("[A-Za-z0-9]+(?:'[A-Za-z0-9]+)*|[.,;:!?]")

        fun tokenizeText(text: String): List<TokenInfo> {
            val out = ArrayList<TokenInfo>()
            var idx = 0
            for (m in TOKEN_REGEX.findAll(text)) {
                out += TokenInfo(idx++, m.value, m.range.first, m.range.last + 1)
            }
            return out
        }

        fun endsWithSentenceBoundary(text: String): Boolean {
            val trimmed = text.trimEnd()
            if (trimmed.isEmpty()) return true
            if (trimmed.endsWith("...") || trimmed.endsWith("\u2026")) return false
            val last = trimmed.last()
            return last == '.' || last == '!' || last == '?' || last == '\u061F' || last == ';'
        }

        private fun isHardBoundaryStart(rawText: String): Boolean {
            val trimmed = rawText.trimStart()
            if (trimmed.isEmpty()) return false
            val first = trimmed.first()
            if (first == '\u2013' || first == '\u2014' || first == '[' || first == '\u266A' || first == '\u266B') return true
            if (trimmed.length > 2 && trimmed[0].isUpperCase()) {
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx in 1..20 && trimmed.substring(0, colonIdx).all { it.isUpperCase() || it == ' ' }) return true
            }
            return false
        }

        fun computeContentHash(cues: List<CueSlice>): String {
            val combined = cues.joinToString("\u0000") { "${it.cueId}:${AnalysisTextCleanup.clean(it.text)}" }
            return AnalysisCacheKey.sha256(combined)
        }
    }
}