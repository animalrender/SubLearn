package com.sublearn.core.subtitles

import com.sublearn.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRepositoryTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello there.

        2
        00:00:03,000 --> 00:00:05,000
        Nice to meet you.
    """.trimIndent()

    private fun repository(vararg files: Pair<String, String>) = DefaultSubtitleRepository(
        InMemorySubtitleSource(mapOf(*files)),
    )

    @Test
    fun `loads a track and builds blocks`() = runTest {
        val repo = repository("movie.en.srt" to srt)
        val file = repo.fileNamed("movie.en.srt")
        val result = repo.load(file, TrackRole.LEARNING)
        val loaded = (result as AppResult.Success).value
        assertEquals(2, loaded.document.blocks.size)
        assertEquals(SubtitleFormat.SRT, loaded.format)
        assertEquals("en", loaded.track.languageTag)
        assertEquals(TrackOrigin.EXTERNAL_SRT, loaded.track.origin)
        assertEquals("Hello there.", loaded.document.blockAt(1_500)?.text)
    }

    @Test
    fun `empty files produce a warning instead of a crash`() = runTest {
        val repo = repository("empty.srt" to "")
        val loaded = repo.load(repo.fileNamed("empty.srt"), TrackRole.LEARNING).getOrThrow()
        assertTrue(loaded.document.blocks.isEmpty())
        assertTrue(loaded.warnings.isNotEmpty())
    }

    @Test
    fun `auto match suggests the translation layer for a persian file`() = runTest {
        val repo = repository(
            "movie.en.srt" to srt,
            "movie.fa.srt" to srt,
            "movie.1080p.txt" to srt,
        )
        val matches = repo.autoMatch("/videos/movie.mkv", "movie.mkv")
        assertEquals(2, matches.size)
        val persian = matches.first { it.file.name == "movie.fa.srt" }
        assertEquals(TrackRole.TRANSLATION, persian.suggestedRole)
        assertEquals("fa", persian.languageTag)
        assertEquals(TrackRole.LEARNING, matches.first { it.file.name == "movie.en.srt" }.suggestedRole)
    }

    @Test
    fun `language guessing is conservative`() {
        assertEquals("en", SubtitleLanguage.guessFromName("Movie.eng.srt"))
        assertEquals("fa", SubtitleLanguage.guessFromName("movie.persian.sub"))
        assertNull(SubtitleLanguage.guessFromName("movie.srt"))
        assertNull(SubtitleLanguage.guessFromName("Movie.commentary.srt"))
        assertEquals("en", SubtitleLanguage.guessFromName("Movie.1080p.en.srt"))
    }

    @Test
    fun `auto matcher accepts suffixes but not unrelated names`() {
        assertTrue(SubtitleAutoMatcher.matches("Movie", "Movie.en.srt"))
        assertTrue(SubtitleAutoMatcher.matches("Movie.mkv".substringBeforeLast('.'), "Movie [1080p].srt"))
        assertTrue(SubtitleAutoMatcher.matches("Movie", "movie.srt"))
        assertTrue(!SubtitleAutoMatcher.matches("Movie", "OtherMovie.srt"))
    }

    @Test
    fun `srt export round trips cue text and timing`() {
        val cues = listOf(Cue(1, 65_000L, 68_500L, "Hello, world.", "t"))
        val text = SubtitleParsers.toSrt(cues)
        val reparsed = SrtParser.parse(text, "t")
        assertEquals(cues.map { it.text to it.startMs }, reparsed.map { it.text to it.startMs })
        assertEquals(cues.map { it.endMs }, reparsed.map { it.endMs })
    }

    private fun DefaultSubtitleRepository.fileNamed(name: String) = SubtitleFile(key = name, name = name)
}
