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
        assertTrue(result.startsWith("SENTENCE 1."))
        assertTrue(result.endsWith("SENTENCE 120"))
        assertEquals(long.uppercase(), result)
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

    @Test
    fun `orchestrator priority chain stops at first hit and merge all combines deduplicated meanings`() = runTest {
        val packEngine = PackTranslationEngine(isPackReady = { true }) { q ->
            if (q == "give up") {
                PackGlossaryResult(
                    headword = "give up",
                    lemma = "give up",
                    cefr = "B1",
                    phonetic = "/ɡɪv ʌp/",
                    primaryGloss = "دست کشیدن، تسلیم شدن",
                    posGlosses = listOf("VERB" to listOf("دست کشیدن", "تسلیم شدن")),
                    lexiconEntryId = 10L,
                )
            } else {
                null
            }
        }
        val mlKitEngine = MlKitTranslationEngine(
            object : TranslationProvider by EchoProvider() {
                override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): AppResult<String> =
                    AppResult.success("تسلیم شدن، رها کردن")
            },
        )

        var mode = com.sublearn.core.settings.TranslationOrchestrationMode.PRIORITY_CHAIN
        val orchestrator = TranslationOrchestrator(
            engines = mapOf(
                com.sublearn.core.settings.TranslationSourceKind.PACK to packEngine,
                com.sublearn.core.settings.TranslationSourceKind.ML_KIT to mlKitEngine,
            ),
            cache = InMemoryTranslationCacheStore(),
            settingsProvider = {
                com.sublearn.core.settings.TranslationSettings(
                    mode = mode,
                    enabledSources = listOf(
                        com.sublearn.core.settings.TranslationSourceKind.PACK,
                        com.sublearn.core.settings.TranslationSourceKind.ML_KIT,
                    ),
                )
            },
        )

        val chainRes = orchestrator.translateImmediate("give up")
        assertEquals(listOf(com.sublearn.core.settings.TranslationSourceKind.PACK), chainRes.sourcesUsed)
        assertEquals(listOf("دست کشیدن", "تسلیم شدن"), chainRes.mergedMeanings)

        mode = com.sublearn.core.settings.TranslationOrchestrationMode.MERGE_ALL
        val mergeRes = orchestrator.translateImmediate("give up")
        assertEquals(
            listOf(
                com.sublearn.core.settings.TranslationSourceKind.PACK,
                com.sublearn.core.settings.TranslationSourceKind.ML_KIT,
            ),
            mergeRes.sourcesUsed,
        )
        assertEquals(listOf("دست کشیدن", "تسلیم شدن", "رها کردن"), mergeRes.mergedMeanings)
    }

    @Test
    fun `online google engine respects privacy and falls back from primary to client it`() = runBlockingCompat {
        val requestedUrls = ArrayList<String>()
        val dj1Payload = """
            {
              "sentences": [
                {"trans": "دست کشیدن", "orig": "give up", "src_translit": "giv ap"}
              ],
              "dict": [
                {"pos": "verb", "terms": ["دست کشیدن", "رها کردن", "تسلیم شدن"]}
              ]
            }
        """.trimIndent()

        var settings = com.sublearn.core.settings.TranslationSettings(
            enabledSources = listOf(com.sublearn.core.settings.TranslationSourceKind.ONLINE_GOOGLE),
            offlineOnly = true,
            onlinePrivacyAccepted = true,
        )

        val engine = OnlineGoogleTranslationEngine(
            settingsProvider = { settings },
            fetcher = { url, _ ->
                requestedUrls += url
                if (url.contains("client=dict-chrome-ex")) {
                    HttpFetchResult(code = 429, body = "rate limited")
                } else {
                    HttpFetchResult(code = 200, body = dj1Payload)
                }
            },
        )

        // Blocked while offlineOnly = true
        val blocked = engine.translate("give up", "en", "fa")
        assertTrue(blocked.isFailure)
        assertTrue(requestedUrls.isEmpty())

        // Allowed once offlineOnly = false and onlinePrivacyAccepted = true
        settings = settings.copy(offlineOnly = false)
        val hit = engine.translate("give up\nignored second line", "en", "fa").getOrThrow()
        assertEquals(2, requestedUrls.size)
        assertTrue(requestedUrls[0].contains("client=dict-chrome-ex"))
        assertTrue(requestedUrls[1].contains("client=it"))
        assertTrue(!requestedUrls[0].contains("ignored"))
        assertEquals("دست کشیدن", hit.text)
        assertEquals("giv ap", hit.phonetic)
        assertEquals(1, hit.posGlosses.size)
        assertEquals("VERB", hit.posGlosses[0].first)
    }

    private fun runBlockingCompat(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
