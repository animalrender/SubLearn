package com.sublearn.core.translate

import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.Cue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic provider: echoes a marker so assertions can prove the cache and chunking work. */
private class EchoProvider(private val upperCase: Boolean = false) : TranslationProvider {
    var calls = 0
        private set
    var failNext: SubLearnError.Kind? = null

    override val id = "echo"

    override suspend fun isPairAvailable(sourceLanguage: String, targetLanguage: String) = true

    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): AppResult<String> {
        calls++
        failNext?.let { kind ->
            failNext = null
            return AppResult.failure(SubLearnError(kind, "model missing"))
        }
        return AppResult.success(if (upperCase) text.uppercase() else "[$sourceLanguage->$targetLanguage] $text")
    }

    override suspend fun translateAll(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<AppResult<String>> = texts.map { translate(it, sourceLanguage, targetLanguage) }

    override suspend fun downloadModel(
        sourceLanguage: String,
        targetLanguage: String,
        onProgress: (TranslationProgress) -> Unit,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun deleteModel(sourceLanguage: String, targetLanguage: String) = AppResult.success(Unit)

    override suspend fun downloadedPairs() = listOf("en" to "fa")
}

class TranslationServiceTest {
    @Test
    fun `translation is cached and reused`() = runTest {
        val provider = EchoProvider()
        val cache = InMemoryTranslationCacheStore()
        val service = TranslationService(provider, cache) { TranslationOptions("en", "fa") }
        val first = service.translate("Hello").getOrThrow()
        val second = service.translate("Hello").getOrThrow()
        assertEquals(first, second)
        assertEquals(1, provider.calls)
        assertEquals(1, cache.size)
    }

    @Test
    fun `a failed translation is not cached`() = runTest {
        val provider = EchoProvider()
        val cache = InMemoryTranslationCacheStore()
        val service = TranslationService(provider, cache) { TranslationOptions("en", "fa") }
        assertTrue(service.translate("boom").isSuccess)
        provider.failNext = SubLearnError.Kind.ModelMissing
        val missing = service.translate("other")
        assertTrue(missing.errorOrNull()?.kind == SubLearnError.Kind.ModelMissing)
        assertEquals(1, cache.size)
    }

    @Test
    fun `cache trimming keeps the more valuable entries`() = runTest {
        val cache = InMemoryTranslationCacheStore(hardMax = 2)
        cache.put("a", "a", "A", "en", "fa", "echo")
        cache.put("b", "b", "B", "en", "fa", "echo")
        cache.get("a")
        cache.put("c", "c", "C", "en", "fa", "echo")
        assertEquals(2, cache.size)
        assertEquals("A", cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `long text is chunked and re-joined in order`() = runTest {
        val provider = EchoProvider(upperCase = true)
        val service = TranslationService(provider, null) { TranslationOptions("en", "fa") }
        val long = (1..120).joinToString(". ") { "sentence $it" }
        val result = service.translate(long).getOrThrow()
        assertTrue(provider.calls > 1)
        assertTrue(result.startsWith("[EN->FA]"))
        assertTrue(result.contains("SENTENCE 120".lowercase()) || result.contains("SENTENCE 120"))
    }

    @Test
    fun `chunks never split a word and respect the limit`() {
        val service = TranslationService(EchoProvider())
        val text = (1..200).joinToString(" ") { "word$it" }
        val chunks = service.chunksFor(text, maxChars = 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(chunks.joinToString(" "), text)
    }

    @Test
    fun `context line picks the sentence containing the word`() {
        val service = TranslationService(EchoProvider())
        val text = "First sentence here. She ran away from him. Third one."
        assertEquals("She ran away from him.", service.contextLine(text, "ran"))
        assertEquals(text, service.contextLine(text, "missing"))
    }

    @Test
    fun `block translation uses the whole block text`() = runTest {
        val provider = EchoProvider()
        val service = TranslationService(provider, null) { TranslationOptions("en", "fa") }
        val block = SubtitleBlock(0, 0, 1_000, "One two.", "t", listOf(Cue(1, 0, 1_000, "One two.", "t")))
        assertTrue(service.translateBlock(block).getOrThrow().contains("One two."))
    }
}
