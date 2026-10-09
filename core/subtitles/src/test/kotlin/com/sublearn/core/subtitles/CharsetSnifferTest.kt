package com.sublearn.core.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class CharsetSnifferTest {
    @Test
    fun `utf-8 bom is detected and removed`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(StandardCharsets.UTF_8)
        val decoded = CharsetSniffer.decode(bytes)
        assertEquals("hi", decoded.text)
        assertTrue(decoded.hadBom)
        assertEquals("UTF-8", decoded.charset.name())
    }

    @Test
    fun `utf-16 le bom is detected`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(StandardCharsets.UTF_16LE)
        val decoded = CharsetSniffer.decode(bytes)
        assertEquals("hi", decoded.text)
        assertEquals("UTF-16LE", decoded.charset.name())
        assertTrue(decoded.hadBom)
    }

    @Test
    fun `utf-16 be bom is detected`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(StandardCharsets.UTF_16BE)
        val decoded = CharsetSniffer.decode(bytes)
        assertEquals("hi", decoded.text)
        assertEquals("UTF-16BE", decoded.charset.name())
    }

    @Test
    fun `plain utf-8 without bom wins`() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n你好\n"
        val decoded = CharsetSniffer.decode(text.toByteArray(StandardCharsets.UTF_8))
        assertEquals("UTF-8", decoded.charset.name())
        assertTrue(decoded.text.contains("你好"))
    }

    @Test
    fun `persian windows-1256 is decoded as persian`() {
        val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrNull()
        assumeTrue("windows-1256 unavailable in this JVM", cp1256 != null)
        val charset: Charset = cp1256 ?: return
        // Windows-1256 cannot store the Persian yeh (U+06CC): exporters write the Arabic yeh (U+064A),
        // and the sniffer maps it back so the text equals what a UTF-8 file would give.
        val text = "1\n00:00:01,000 --> 00:00:02,000\nسلام دن\u064Aا\n"
        val decoded = CharsetSniffer.decode(text.toByteArray(charset))
        assertEquals("windows-1256", decoded.charset.name())
        assertEquals("سلام دن\u06CCا", decoded.text.lines()[2].trim())
        assertTrue(TextDirection.of(decoded.text) == TextDirection.RTL)
    }

    @Test
    fun `utf-16 without bom is recognised by its null pattern`() {
        val bytes = "abc def ghi".toByteArray(StandardCharsets.UTF_16LE)
        val decoded = CharsetSniffer.decode(bytes)
        assertTrue(decoded.text.startsWith("abc"))
        assertEquals("UTF-16LE", decoded.charset.name())
        val big = CharsetSniffer.decode("abc def ghi".toByteArray(StandardCharsets.UTF_16BE))
        assertEquals("abc def ghi", big.text)
    }
}
