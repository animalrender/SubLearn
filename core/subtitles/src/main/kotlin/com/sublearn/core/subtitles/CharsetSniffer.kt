package com.sublearn.core.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes subtitle bytes without asking the user, because a wrong charset makes Persian
 * subtitles unreadable and no Android API detects one (SUB-7).
 *
 * Order: byte-order mark, UTF-16 heuristic (NUL bytes are valid UTF-8, so this must come first),
 * UTF-8 (strict), then the legacy Windows code page that Persian SRT files were exported with most
 * often, then Latin-1 as the never-failing fallback.
 */
object CharsetSniffer {
    private val windows1256: Charset by lazy { lookup("windows-1256") ?: lookup("CP1256") ?: StandardCharsets.ISO_8859_1 }
    private val windows1250: Charset by lazy { lookup("windows-1250") ?: StandardCharsets.ISO_8859_1 }

    data class Decoded(val text: String, val charset: Charset, val hadBom: Boolean)

    private fun lookup(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }

    fun decode(bytes: ByteArray): Decoded {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return Decoded(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, true)

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return Decoded(decodeUtf16(bytes.copyOfRange(2, bytes.size), littleEndian = true), StandardCharsets.UTF_16LE, true)

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return Decoded(decodeUtf16(bytes.copyOfRange(2, bytes.size), littleEndian = false), StandardCharsets.UTF_16BE, true)
        }

        if (looksLikeUtf16(bytes)) {
            // Little endian puts the high (zero) byte of a Latin character second, i.e. at odd offsets.
            val little = countNulOnOdd(bytes) > countNulOnEven(bytes)
            val charset = if (little) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
            return Decoded(decodeUtf16(bytes, little), charset, false)
        }

        decodeStrict(bytes, StandardCharsets.UTF_8)?.let { return Decoded(it, StandardCharsets.UTF_8, false) }

        // Persian subtitles exported on Windows are commonly windows-1256. Prefer it when it
        // produces a plausible Arabic-script text, otherwise fall back to windows-1250 for
        // Central-European subtitles, then Latin-1 so decoding never fails.
        decodeStrict(bytes, windows1256)?.let { decoded ->
            if (decoded.any { TextDirection.isRtlChar(it) }) return Decoded(decoded, windows1256, false)
        }
        decodeStrict(bytes, windows1250)?.let { decoded ->
            if (decoded.any { it.code in 0x0100..0x017F }) return Decoded(decoded, windows1250, false)
        }
        val fallback = String(bytes, StandardCharsets.ISO_8859_1)
        return Decoded(fallback, StandardCharsets.ISO_8859_1, false)
    }

    fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean): String {
        val charset = if (littleEndian) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
        return String(bytes, charset)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun looksLikeUtf16(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        val total = minOf(bytes.size, 512)
        val nulEven = countNulOnEven(bytes, total)
        val nulOdd = countNulOnOdd(bytes, total)
        val half = total / 2
        return half > 0 && (nulEven.toDouble() / half > 0.5 || nulOdd.toDouble() / half > 0.5)
    }

    private fun countNulOnEven(bytes: ByteArray, limit: Int = minOf(bytes.size, 512)): Int =
        (0 until limit step 2).count { bytes[it] == 0.toByte() }

    private fun countNulOnOdd(bytes: ByteArray, limit: Int = minOf(bytes.size, 512)): Int =
        (1 until limit step 2).count { bytes[it] == 0.toByte() }
}
