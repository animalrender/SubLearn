package com.sublearn.core.lexicon

import com.sublearn.core.common.FeatureFlag
import com.sublearn.core.settings.CefrLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    @Test
    fun `frequency list parses levels`() {
        val parsed = FrequencyListFormat.parse(
            """
            # a tiny list
            the,A1
            cat,A1
            although,B2
            procrastinate,	C2
            nonsense-without-level
            """.trimIndent(),
        )
        assertEquals(4, parsed.entries.size)
        assertEquals(CefrLevel.B2, parsed.entries["although"])
        assertEquals(CefrLevel.C2, parsed.entries["procrastinate"])
        assertEquals(1, parsed.rejected)
        assertTrue(parsed.warnings.single().contains("nonsense-without-level"))
    }

    @Test
    fun `frequency list parses counts and ignores a header row`() {
        val parsed = FrequencyListFormat.parse(
            """
            word,frequency
            the,1000000
            although,2500
            exotic,12
            """.trimIndent(),
        )
        assertEquals(CefrLevel.A1, parsed.entries["the"])
        assertEquals(CefrLevel.B2, parsed.entries["although"])
        assertEquals(CefrLevel.C2, parsed.entries["exotic"])
        assertEquals(0, parsed.rejected)
    }

    @Test
    fun `rank buckets are monotonic`() {
        assertEquals(CefrLevel.A1, FrequencyListFormat.levelFromRank(1, 10_000))
        assertTrue(FrequencyListFormat.levelFromRank(9_000, 10_000).ordinal > FrequencyListFormat.levelFromRank(500, 10_000).ordinal)
        assertEquals(CefrLevel.UNKNOWN, FrequencyListFormat.levelFromRank(5, 0))
    }

    @Test
    fun `known words provider treats marked words as known`() = runTest {
        val provider = KnownWordsLevelProvider({ setOf("although") }, unmarkedLevel = CefrLevel.B2)
        assertTrue(!provider.isAboveLevel("although", CefrLevel.A2))
        assertTrue(provider.isAboveLevel("procrastinate", CefrLevel.A2))
        assertTrue(!provider.isAboveLevel("  ", CefrLevel.A2))
        assertEquals(CefrLevel.B2, provider.level("whatever"))
    }

    @Test
    fun `chain keeps the first answer`() = runTest {
        val chain = ChainedWordLevelProvider(
            listOf(ManualLevelProvider(CefrLevel.UNKNOWN), FrequencyListLevelProvider(mapOf("dog" to CefrLevel.A1))),
        )
        assertEquals(CefrLevel.A1, chain.level("dog"))
        assertNull(chain.level("cat"))
    }

    @Test
    fun `stubs refuse instead of pretending`() = runTest {
        assertTrue(runCatching { NotImplementedWordAnalyzer().analyze(com.sublearn.core.subtitles.SubtitleBlock(0, 0, 1, "x", "t", emptyList()), "w") }.exceptionOrNull() is NotImplementedError)
        assertTrue(runCatching { NotImplementedSpeechToText().generate("u", "en") }.exceptionOrNull() is NotImplementedError)
        assertTrue(runCatching { NotImplementedUpdateChecker().check("0.1.0") }.exceptionOrNull() is NotImplementedError)
        assertTrue(runCatching { NotImplementedLevelDetector().detect(listOf("a")) }.exceptionOrNull() is NotImplementedError)
        assertEquals(false, NotImplementedDictionaryProvider().isAvailable())
        assertEquals(FeatureFlag.POS_ANALYSIS, NotImplementedWordAnalyzer().feature)
    }
}
