package com.sublearn.core.lexicon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.sublearn.core.common.AppResult
import com.sublearn.core.common.SubLearnError
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Pure-JVM codec for decoding `.bipe` (`fastdic_plain`) or raw SQLite streams into a valid SQLite
 * file in 64 KB chunks without holding the 98 MB database in memory (D-61).
 */
object BipeCodec {
    private val KEY = "Fastdic@1995".toByteArray(Charsets.US_ASCII)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Reads [input] (which may be a `.bipe` file with `BIPE` header, a headerless XOR+zlib
     * `fastdic_plain` stream, or a raw `SQLite format 3` file), writes the decoded SQLite database
     * to [output], and returns the SHA-256 hex digest of the output SQLite file.
     */
    fun decodeToSqlite(input: InputStream, output: OutputStream): String {
        val pushback = PushbackInputStream(input, 16)
        val header = ByteArray(16)
        var readTotal = 0
        while (readTotal < header.size) {
            val r = pushback.read(header, readTotal, header.size - readTotal)
            if (r < 0) break
            readTotal += r
        }
        if (readTotal < 8) {
            throw IllegalArgumentException("Input file is too short to be a valid dictionary ($readTotal bytes)")
        }

        val sourceStream: InputStream = when {
            // Case 1: Raw SQLite file
            readTotal == 16 && header.contentEquals(SQLITE_MAGIC) -> {
                pushback.unread(header, 0, readTotal)
                pushback
            }
            // Case 2: 'BIPE' 8-byte header (4-byte magic + 4-byte uint32 length)
            header[0] == 'B'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'P'.code.toByte() &&
                header[3] == 'E'.code.toByte() -> {
                if (readTotal > 8) {
                    pushback.unread(header, 8, readTotal - 8)
                }
                InflaterInputStream(XorInputStream(pushback, KEY), java.util.zip.Inflater(), BUFFER_SIZE)
            }
            // Case 3: Headerless XOR+zlib ('fastdic_plain' where byte 0 xor 'F' == 0x78)
            ((header[0].toInt() xor KEY[0].toInt()) and 0xFF) == 0x78 -> {
                pushback.unread(header, 0, readTotal)
                InflaterInputStream(XorInputStream(pushback, KEY), java.util.zip.Inflater(), BUFFER_SIZE)
            }
            else -> throw IllegalArgumentException("Unrecognized file header; expected SQLite or .bipe format")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        var firstChunk = true
        while (true) {
            val n = sourceStream.read(buf)
            if (n < 0) break
            if (n == 0) continue
            if (firstChunk) {
                firstChunk = false
                if (n < 16 || !buf.copyOfRange(0, 16).contentEquals(SQLITE_MAGIC)) {
                    throw IllegalArgumentException("Decoded stream does not start with SQLite format 3 header")
                }
            }
            output.write(buf, 0, n)
            digest.update(buf, 0, n)
        }
        output.flush()
        if (firstChunk) {
            throw IllegalArgumentException("Decoded stream was empty")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Encode a raw SQLite byte array into `.bipe` format (used by unit tests). */
    fun encodeToBipe(sqliteBytes: ByteArray): ByteArray {
        val compressed = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.DeflaterOutputStream(baos).use { dos -> dos.write(sqliteBytes) }
            baos.toByteArray()
        }
        val xored = ByteArray(compressed.size) { idx ->
            (compressed[idx].toInt() xor KEY[idx % KEY.size].toInt()).toByte()
        }
        val out = ByteArray(8 + xored.size)
        out[0] = 'B'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'P'.code.toByte()
        out[3] = 'E'.code.toByte()
        val len = xored.size
        out[4] = ((len ushr 24) and 0xFF).toByte()
        out[5] = ((len ushr 16) and 0xFF).toByte()
        out[6] = ((len ushr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()
        System.arraycopy(xored, 0, out, 8, xored.size)
        return out
    }

    private class XorInputStream(
        delegate: InputStream,
        private val key: ByteArray,
    ) : FilterInputStream(delegate) {
        private var offset: Int = 0

        override fun read(): Int {
            val b = super.read()
            if (b < 0) return b
            val decoded = (b xor (key[offset % key.size].toInt() and 0xFF)) and 0xFF
            offset = (offset + 1) % key.size
            return decoded
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count <= 0) return count
            for (i in 0 until count) {
                val k = key[offset].toInt()
                b[off + i] = (b[off + i].toInt() xor k).toByte()
                offset++
                if (offset == key.size) offset = 0
            }
            return count
        }
    }
}

/**
 * Imports a legacy v1 FastDic database (`main_db.sqlite` or `.bipe` / `fastdic_plain`) into the
 * app's `packs/` directory and registers it as `PackKind.LEGACY_V1` (D-61).
 */
class LegacyV1Importer(
    private val context: Context,
    private val packRepository: InstalledPackRepository,
    private val lexiconAccess: PackLexiconAccess,
) {
    suspend fun importUri(uriString: String): AppResult<LexiconImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val input = if (uri.scheme == "file" && uri.path != null) {
                FileInputStream(File(uri.path!!))
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return@withContext AppResult.failure(
                SubLearnError(SubLearnError.Kind.NotFound, "Cannot open selected legacy dictionary file"),
            )

            input.use { stream -> importStream(stream) }
        }.getOrElse { t ->
            AppResult.failure(
                SubLearnError(
                    SubLearnError.Kind.MalformedInput,
                    "Failed to import Legacy v1 dictionary: ${t.message}",
                    t,
                ),
            )
        }
    }

    suspend fun importFile(file: File): AppResult<LexiconImportResult> = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext AppResult.failure(SubLearnError(SubLearnError.Kind.NotFound, "File not found: ${file.path}"))
        }
        runCatching {
            FileInputStream(file).use { stream -> importStream(stream) }
        }.getOrElse { t ->
            AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "Invalid legacy v1 file: ${t.message}", t))
        }
    }

    private suspend fun importStream(input: InputStream): AppResult<LexiconImportResult> {
        val packsDir = File(context.filesDir, "packs").apply { mkdirs() }
        val tempFile = File(packsDir, "legacy-fastdic-v1.sqlite.tmp")
        val targetFile = File(packsDir, "legacy-fastdic-v1.sqlite")

        val sha256 = try {
            FileOutputStream(tempFile).use { out ->
                BipeCodec.decodeToSqlite(input, out)
            }
        } catch (t: Throwable) {
            tempFile.delete()
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "Could not decode legacy v1 file: ${t.message}", t),
            )
        }

        // Verify legacy v1 schema (english_words + english_details)
        var wordCount = 0
        try {
            val db = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use { sqlite ->
                val tables = HashSet<String>()
                sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                    while (c.moveToNext()) tables += c.getString(0)
                }
                if ("english_words" !in tables || "english_details" !in tables) {
                    tempFile.delete()
                    return AppResult.failure(
                        SubLearnError(
                            SubLearnError.Kind.MalformedInput,
                            "Legacy database is missing english_words or english_details tables",
                        ),
                    )
                }
                sqlite.rawQuery("SELECT COUNT(*) FROM english_words", null).use { c ->
                    if (c.moveToFirst()) wordCount = c.getInt(0)
                }
            }
        } catch (t: Throwable) {
            tempFile.delete()
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "SQLite validation failed: ${t.message}", t),
            )
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        val entity = InstalledPackEntity(
            packId = "legacy-fastdic-v1",
            kind = PackKind.LEGACY_V1.id,
            version = "1.0.0",
            titleEn = "FastDic Legacy v1 Dictionary",
            titleFa = "لغت‌نامهٔ فست‌دیک (نسخهٔ ۱)",
            localPath = targetFile.absolutePath,
            sizeBytes = targetFile.length(),
            sha256 = sha256,
            entryCount = wordCount,
            sourceLang = "en",
            targetLang = "fa",
            enabled = true,
            installedAt = System.currentTimeMillis(),
        )
        packRepository.register(entity)
        lexiconAccess.reloadFromRepository()

        val meta = LexiconV2Meta(
            formatVersion = 1,
            packId = entity.packId,
            direction = "en-fa",
            sourceLang = "en",
            glossLang = "fa",
            entryCount = wordCount,
            builtAt = "",
            builderVersion = "legacy-v1",
        )
        return AppResult.success(
            LexiconImportResult(
                success = true,
                packId = entity.packId,
                kind = PackKind.LEGACY_V1,
                meta = meta,
            ),
        )
    }
}
