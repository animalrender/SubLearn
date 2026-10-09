package com.sublearn.core.ai

import com.sublearn.core.common.TimeUtils
import com.sublearn.core.subtitles.SubtitleBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    private class FakeClient(
        val status: Int = 200,
        val body: String,
        val throwOnPost: Boolean = false,
    ) : HttpJsonClient {
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()

        override suspend fun post(url: String, headers: Map<String, String>, bodyJson: String): HttpJsonResponse {
            if (throwOnPost) throw java.io.IOException("no network")
            lastUrl = url
            lastBody = bodyJson
            lastHeaders = headers
            return HttpJsonResponse(status, body)
        }
    }

    private fun block(id: Long, start: Long, end: Long, text: String) =
        SubtitleBlock(id, start, end, text, "t", emptyList())

    // ---------------------------------------------------------------- providers

    @Test
    fun `gemini request carries prompt, config and key header`() = runTest {
        val client = FakeClient(body = """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":42}}""")
        val provider = GeminiProvider(client)
        val result = provider.complete(AiRequest(userPrompt = "Explain", systemPrompt = "Be brief", temperature = 0.2f, maxOutputTokens = 300), "secret-key", null)
        val answer = result.getOrThrow()
        assertEquals("Hi", answer.text)
        assertEquals(42, answer.usageTokens)
        assertEquals("STOP", answer.finishReason)
        assertEquals("secret-key", client.lastHeaders["x-goog-api-key"])
        assertTrue(client.lastUrl!!.endsWith(":generateContent"))
        assertTrue(client.lastBody!!.contains("Explain"))
        assertTrue(client.lastBody!!.contains("Be brief"))
        assertTrue(client.lastBody!!.contains("maxOutputTokens"))
    }

    @Test
    fun `openai and anthropic shapes are parsed`() = runTest {
        val openAi = FakeClient(body = """{"choices":[{"message":{"content":"A gloss"},"finish_reason":"stop"}],"usage":{"total_tokens":7}}""")
        val openAiAnswer = OpenAiCompatibleProvider(openAi).complete(AiRequest("q"), "k", null).getOrThrow()
        assertEquals("A gloss", openAiAnswer.text)
        assertEquals(7, openAiAnswer.usageTokens)
        assertEquals("Bearer k", openAi.lastHeaders["Authorization"])

        val anthropic = FakeClient(body = """{"content":[{"type":"thinking","text":"no"},{"type":"text","text":"Because idioms"},{"stop_reason":"end_turn"}]}""")
        val claude = AnthropicProvider(anthropic).complete(AiRequest("q"), "k", null).getOrThrow()
        assertEquals("Because idioms", claude.text)
        assertEquals("2023-06-01", anthropic.lastHeaders["anthropic-version"])
    }

    @Test
    fun `http and network errors map to kinds`() = runTest {
        val unauthorized = GeminiProvider(FakeClient(status = 401, body = """{"error":{"message":"API key not valid"}}"""))
            .complete(AiRequest("q"), "bad", null).errorOrNull()
        assertEquals(SubLearnErrorKindCheck.UNAUTHORIZED, unauthorized?.kind?.name)
        assertTrue(unauthorized!!.message.contains("API key not valid"))

        val limited = GeminiProvider(FakeClient(status = 429, body = "not json"))
            .complete(AiRequest("q"), "k", null).errorOrNull()
        assertEquals("RateLimited", limited?.kind?.name)

        val offline = GeminiProvider(FakeClient(status = 200, body = "{}", throwOnPost = true))
            .complete(AiRequest("q"), "k", null).errorOrNull()
        assertEquals("NetworkUnavailable", offline?.kind?.name)
    }

    @Test
    fun `empty text is an error, not a blank answer`() = runTest {
        val result = GeminiProvider(FakeClient(body = """{"candidates":[{"content":{"parts":[{"text":"   "}]}}]}"""))
            .complete(AiRequest("q"), "k", null)
        assertNotNull(result.errorOrNull())
    }

    @Test
    fun `insecure endpoints are refused before any request`() {
        assertFalse(AiEndpointPolicy.isSafe("http://evil.example"))
        assertFalse(AiEndpointPolicy.isSafe("https://user:pw@example.com"))
        assertTrue(AiEndpointPolicy.isSafe("https://api.openai.com"))
        runCatching { AiEndpointPolicy.requireHttps("http://x") }.onFailure { assertTrue(it is IllegalArgumentException) }
    }

    // ---------------------------------------------------------------- prompts

    @Test
    fun `template rendering substitutes known variables and keeps unknown ones`() {
        val rendered = AiPromptBuilder.render(
            "Say ${'$'}{selected} for ${'$'}{nativeLanguage} at ${'$'}{level}; ${'$'}{mystery}",
            mapOf("selected" to "break a leg", "nativeLanguage" to "Persian", "level" to "B2"),
        )
        assertTrue(rendered.startsWith("Say break a leg for Persian at B2"))
        assertTrue(rendered.contains("mystery"))
        assertEquals(listOf("mystery"), AiPromptBuilder.unknownVariables("Say ${'$'}{selected}; ${'$'}{mystery}"))
    }

    @Test
    fun `prompt request falls back from selection to the whole block`() {
        val request = AiPromptRequest(
            userTemplate = "Q: ${'$'}{selected}",
            selectedText = null,
            block = block(0, 1_000, 3_000, "Full block text"),
        )
        assertEquals("Q: Full block text", AiPromptBuilder.build(request).userPrompt)

        val withSelection = request.copy(selectedText = "a word")
        assertEquals("Q: a word", AiPromptBuilder.build(withSelection).userPrompt)
    }

    @Test
    fun `context builder takes the blocks before the current one`() {
        val blocks = (0 until 5).map { block(it.toLong(), it * 5_000L, it * 5_000L + 1_000, "line $it") }
        assertEquals(listOf("line 2"), AiContextBuilder.before(blocks, 3, 1).map { it.text })
        assertEquals(listOf("line 0", "line 1", "line 2"), AiContextBuilder.before(blocks, 3, 10).map { it.text })
        assertTrue(AiContextBuilder.before(blocks, 0, 3).isEmpty())
        val rendered = AiContextBuilder.render(listOf(block(0, 65_000L, 66_000L, "one")))
        assertTrue(rendered.contains("[1:05]"))
        assertEquals("Title: Movie\nPosition: 0:05", AiContextBuilder.header("Movie", 5_000L, true, true))
        assertNull(AiContextBuilder.header(null, null, true, true))
    }

    @Test
    fun `answer parser splits the four sections and tolerates missing ones`() {
        val answer = """
            **1. Meaning here**
            It means to quit something.

            ## 2. Why it is used
            It is informal and a bit dramatic.

            3. Near synonyms:
            stop, cease

            Something the model added that has no heading.
        """.trimIndent()
        val sections = AiAnswerParser.parse(answer)
        assertEquals("It means to quit something.", sections.meaning)
        assertEquals("It is informal and a bit dramatic.", sections.whyUsed)
        assertEquals("stop, cease", sections.synonyms)
        assertNull(sections.elsewhere)
        assertTrue(sections.extra!!.contains("no heading"))
    }

    @Test
    fun `middle sections keep their paragraphs and a preamble is extra`() {
        val answer = """
            Sure, here is the breakdown.

            **Meaning here**
            First paragraph.

            Second paragraph of the meaning.

            **Why it is used**
            Because.
        """.trimIndent()
        val sections = AiAnswerParser.parse(answer)
        assertEquals("First paragraph.\n\nSecond paragraph of the meaning.", sections.meaning)
        assertEquals("Because.", sections.whyUsed)
        assertEquals("Sure, here is the breakdown.", sections.extra)
    }

    @Test
    fun `the default template substitutes the selection`() {
        val request = AiPromptRequest(userTemplate = "", selectedText = "break a leg", block = null)
        assertEquals("Explain this line for a learner: break a leg", AiPromptBuilder.build(request).userPrompt)
    }

    @Test
    fun `a plain answer becomes the meaning section`() {
        val sections = AiAnswerParser.parse("Just a normal explanation with no headings at all.")
        assertEquals("Just a normal explanation with no headings at all.", sections.meaning)
        assertFalse(sections.isEmpty)
    }

    @Test
    fun `heading detection ignores body text`() {
        assertNull(AiAnswerParser.headingKey("This is a sentence that mentions why it matters."))
        assertEquals("why", AiAnswerParser.headingKey("Why it is used:"))
        assertEquals("meaning", AiAnswerParser.headingKey("### 1. Meaning here"))
    }

    @Test
    fun `clock formatting is stable for timestamps`() {
        assertEquals("0:00", TimeUtils.formatClock(0L))
        assertEquals("1:05", TimeUtils.formatClock(65_000L))
        assertEquals("1:00:05", TimeUtils.formatClock(3_605_000L))
    }

    private object SubLearnErrorKindCheck {
        const val UNAUTHORIZED = "Unauthorized"
    }
}
