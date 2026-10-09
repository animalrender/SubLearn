package com.sublearn.core.ai

import com.sublearn.core.common.TimeUtils
import com.sublearn.core.subtitles.SubtitleBlock

/**
 * Prompt assembly for the AI button (AI-2, AI-4).
 *
 * A user-editable template with `${'$'}{placeholder}` variables: editable but not executable, so a
 * broken template can never crash the app or run anything. Unknown placeholders are left in place
 * and reported, which is what the prompt editor preview shows.
 */
object AiPromptBuilder {
    val KNOWN_VARIABLES = listOf(
        "selected",
        "block",
        "context",
        "title",
        "timestamp",
        "learningLanguage",
        "nativeLanguage",
        "level",
        "mode",
        "word",
    )

    private val placeholder = Regex("""\$\{\s*([A-Za-z0-9_.]+)\s*}""")

    fun variablesIn(template: String): List<String> = placeholder.findAll(template).map { it.groupValues[1] }.toList()

    fun unknownVariables(template: String): List<String> = variablesIn(template).filterNot { KNOWN_VARIABLES.contains(it) }

    /**
     * Substitutes every known variable. Missing values become an empty string for blocks that are
     * optional (context, title) so a short prompt stays readable instead of showing blanks.
     */
    fun render(template: String, values: Map<String, String>): String {
        val text = StringBuilder(template)
        // Replace from the end so offsets stay valid when lengths change.
        val matches = placeholder.findAll(template).toList().reversed()
        for (match in matches) {
            val name = match.groupValues[1]
            val replacement = values[name] ?: match.value
            text.replace(match.range.first, match.range.last + 1, replacement)
        }
        return text.toString()
    }

    fun build(request: AiPromptRequest): AiRequest {
        val values = request.values()
        val prompt = render(request.userTemplate.ifBlank { DEFAULT_USER_TEMPLATE }, values).trim()
        val system = request.systemTemplate?.takeIf { it.isNotBlank() }?.let { render(it, values).trim() }
        return AiRequest(
            userPrompt = prompt,
            systemPrompt = system,
            temperature = request.temperaturePercent / 100f,
            maxOutputTokens = request.maxOutputTokens,
            model = request.model,
        )
    }

    const val DEFAULT_USER_TEMPLATE = "Explain this line for a learner: \${selected}"

    /** Placeholders the editor offers; keeps the settings screen and the builder in sync. */
    val HINTS: Map<String, String> = mapOf(
        "selected" to "the text the user selected, or the whole block",
        "block" to "the full subtitle block",
        "context" to "the previous blocks of subtitles",
        "title" to "the video title",
        "timestamp" to "the position of the block in the video",
        "learningLanguage" to "language being learned",
        "nativeLanguage" to "the user's own language",
        "level" to "the learner's level",
        "mode" to "entertainment or learning",
        "word" to "the tapped word, when there is one",
    )
}

/** Everything the template can reference; built by the player screen. */
data class AiPromptRequest(
    val userTemplate: String,
    val systemTemplate: String? = null,
    val selectedText: String?,
    val block: SubtitleBlock?,
    val context: List<SubtitleBlock> = emptyList(),
    val title: String? = null,
    val learningLanguage: String = "English",
    val nativeLanguage: String = "Persian",
    val level: String = "B1",
    val mode: String = "entertainment",
    val temperaturePercent: Int = 30,
    val maxOutputTokens: Int = 700,
    val model: String? = null,
) {
    fun values(): Map<String, String> {
        val blockText = block?.text.orEmpty()
        return mapOf(
            "selected" to (selectedText?.takeIf { it.isNotBlank() } ?: blockText),
            "block" to blockText,
            "context" to AiContextBuilder.render(context),
            "title" to (title.orEmpty()),
            "timestamp" to (block?.let { TimeUtils.formatClock(it.startMs) }.orEmpty()),
            "learningLanguage" to learningLanguage,
            "nativeLanguage" to nativeLanguage,
            "level" to level,
            "mode" to mode,
            "word" to (selectedText?.takeIf { it.isNotBlank() && it.length <= 60 } ?: ""),
        )
    }
}

/** AI-4: the previous N blocks plus title and timestamps, formatted so the model cites them back. */
object AiContextBuilder {
    fun render(blocks: List<SubtitleBlock>): String = blocks.joinToString("\n") { block ->
        "[${TimeUtils.formatClock(block.startMs)}] ${block.text}"
    }

    /**
     * Builds the context window: [count] blocks ending before [blockIndex]. A negative or missing
     * index yields nothing rather than guessing, because a wrong context is worse than none.
     */
    fun before(blocks: List<SubtitleBlock>, blockIndex: Int, count: Int): List<SubtitleBlock> {
        if (blocks.isEmpty() || blockIndex <= 0 || count <= 0) return emptyList()
        val from = (blockIndex - count).coerceIn(0, blocks.lastIndex)
        return blocks.subList(from, blockIndex.coerceAtMost(blocks.size))
    }

    /** Header block that tells the model what it is looking at; kept short on purpose. */
    fun header(title: String?, startMs: Long?, includeTitle: Boolean, includeTimestamps: Boolean): String? {
        val parts = buildList {
            if (includeTitle && !title.isNullOrBlank()) add("Title: $title")
            if (includeTimestamps && startMs != null) add("Position: ${TimeUtils.formatClock(startMs)}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

/**
 * Splits an answer into the four parts of AI-3. Model output is not trusted to be well formed, so
 * this is a tolerant parser: markdown headings, bold headings and numbered lines all count, and a
 * response with no recognisable heading is shown as a single "meaning" block.
 */
object AiAnswerParser {
    private val labels = mapOf(
        "meaning here" to "meaning",
        "meaning in this moment" to "meaning",
        "meaning" to "meaning",
        "why it is used" to "why",
        "why it is used here" to "why",
        "why" to "why",
        "near synonyms" to "synonyms",
        "synonyms" to "synonyms",
        "where else it is used" to "elsewhere",
        "where else" to "elsewhere",
        "examples" to "elsewhere",
        "where it appears" to "elsewhere",
    )

    private val numbered = Regex("^\\d{1,2}[.)]\\s+")

    /**
     * Text before the first heading and any paragraph that follows the last section's own paragraph
     * are "extra": a model that appends a note after the four sections should not have that note
     * glued to the last section's content. Sections in the middle keep all their paragraphs.
     */
    fun parse(text: String): AiAnswerSections {
        if (text.isBlank()) return AiAnswerSections.EMPTY
        val lines = text.lines()
        val lastHeading = lines.indexOfLast { headingKey(it) != null }
        if (lastHeading < 0) return AiAnswerSections(meaning = text.trim())
        val buckets = LinkedHashMap<String, StringBuilder>()
        val preamble = StringBuilder()
        var current: String? = null
        var paragraphsInCurrent = 0
        var afterBlankLine = false
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            val heading = headingKey(line)
            if (heading != null) {
                current = heading
                paragraphsInCurrent = 0
                afterBlankLine = false
                val rest = remainderAfterHeading(line)
                if (rest.isNotEmpty()) {
                    buckets.getOrPut(heading) { StringBuilder() }.append(rest).append('\n')
                    paragraphsInCurrent = 1
                }
                continue
            }
            if (line.isEmpty()) {
                afterBlankLine = true
                continue
            }
            val key = current
            when {
                key == null -> preamble.append(line).append('\n')
                index > lastHeading && afterBlankLine && paragraphsInCurrent > 0 -> {
                    buckets.getOrPut(EXTRA) { StringBuilder() }.append(line).append('\n')
                    current = EXTRA
                }
                else -> {
                    if (afterBlankLine || paragraphsInCurrent == 0) paragraphsInCurrent++
                    val target = buckets.getOrPut(key) { StringBuilder() }
                    if (afterBlankLine && target.isNotEmpty()) target.append('\n')
                    target.append(line).append('\n')
                }
            }
            afterBlankLine = false
        }
        if (preamble.isNotBlank()) buckets.getOrPut(EXTRA) { StringBuilder() }.insert(0, preamble.toString().trim() + "\n")

        fun take(key: String): String? = buckets[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        return AiAnswerSections(
            meaning = take("meaning"),
            whyUsed = take("why"),
            synonyms = take("synonyms"),
            elsewhere = take("elsewhere"),
            extra = take(EXTRA),
        )
    }

    private const val EXTRA = "extra"

    /** Returns the section key when the line looks like a heading, otherwise null. */
    internal fun headingKey(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.length < 3 || trimmed.length > 60) return null
        val looksLikeHeading = trimmed.startsWith("#") ||
            trimmed.startsWith("**") ||
            trimmed.endsWith(":") ||
            numbered.containsMatchIn(trimmed)
        if (!looksLikeHeading) return null
        var label = trimmed.trimStart('#', ' ', '*', '>')
        label = numbered.replace(label, "")
        label = label.substringBefore(':').trim().removeSuffix("**").trim()
        if (label.length !in 3..40) return null
        val lower = label.lowercase()
        return labels[lower] ?: labels.entries.firstOrNull { lower.startsWith(it.key) }?.value
    }

    internal fun remainderAfterHeading(line: String): String {
        var rest = line.trim().trimStart('#', ' ', '*', '>')
        rest = numbered.replace(rest, "")
        val colon = rest.indexOf(':')
        return if (colon >= 0) rest.substring(colon + 1).trim().removeSuffix("**").trim() else ""
    }
}
