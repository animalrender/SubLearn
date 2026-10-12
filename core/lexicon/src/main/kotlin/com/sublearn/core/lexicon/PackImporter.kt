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
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Imports SubLearn Lexicon Pack v2 (`.sqlite` or `.sqlite.gz`) for `LEXICON_EN_FA` or `DICT_FA_EN`,
 * with automatic fallback to [LegacyV1Importer] if the user selects a `.bipe` (`fastdic_plain`)
 * or raw v1 `main_db.sqlite` file (Phase 10, D-43, D-58, D-61).
 */
class PackImporter(
    private val context: Context,
    private val packRepository: InstalledPackRepository,
    private val lexiconAccess: PackLexiconAccess,
    private val legacyImporter: LegacyV1Importer = LegacyV1Importer(context, packRepository, lexiconAccess),
) {
    suspend fun importUri(uriString: String, expectedSha256: String? = null): AppResult<LexiconImportResult> =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString)
            val openStream: () -> InputStream? = {
                if (uri.scheme == "file" && uri.path != null) {
                    FileInputStream(File(uri.path!!))
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            }
            val firstStream = openStream() ?: return@withContext AppResult.failure(
                SubLearnError(SubLearnError.Kind.NotFound, "Cannot open selected pack file"),
            )
            val v2Result = firstStream.use { stream -> importStream(stream, expectedSha256) }
            if (v2Result.isSuccess) return@withContext v2Result

            // Fallback: check if it's a Legacy v1 .bipe or main_db.sqlite file
            val secondStream = openStream() ?: return@withContext v2Result
            secondStream.use {
                val legacyRes = legacyImporter.importUri(uriString)
                if (legacyRes.isSuccess) legacyRes else v2Result
            }
        }

    suspend fun importFile(file: File, expectedSha256: String? = null): AppResult<LexiconImportResult> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                return@withContext AppResult.failure(
                    SubLearnError(SubLearnError.Kind.NotFound, "Pack file does not exist: ${file.path}"),
                )
            }
            val v2Result = FileInputStream(file).use { stream -> importStream(stream, expectedSha256) }
            if (v2Result.isSuccess) return@withContext v2Result
            val legacyResult = legacyImporter.importFile(file)
            if (legacyResult.isSuccess) legacyResult else v2Result
        }

    private suspend fun importStream(
        rawInput: InputStream,
        expectedSha256: String? = null,
    ): AppResult<LexiconImportResult> {
        val packsDir = File(context.filesDir, "packs").apply { mkdirs() }
        val tempFile = File(packsDir, "import-${System.currentTimeMillis()}.sqlite.tmp")

        try {
            val pushback = PushbackInputStream(rawInput, 16)
            val magic = ByteArray(2)
            val read = pushback.read(magic)
            if (read < 2) {
                return AppResult.failure(SubLearnError(SubLearnError.Kind.MalformedInput, "Pack file is empty"))
            }
            pushback.unread(magic, 0, read)

            val isGzip = (magic[0].toInt() and 0xFF) == 0x1F && (magic[1].toInt() and 0xFF) == 0x8B
            val source: InputStream = if (isGzip) GZIPInputStream(pushback, 64 * 1024) else pushback

            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var firstChunk = true

            FileOutputStream(tempFile).use { out ->
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (firstChunk) {
                        firstChunk = false
                        val headerStr = String(buf, 0, minOf(n, 15), Charsets.US_ASCII)
                        if (!headerStr.startsWith("SQLite format 3")) {
                            tempFile.delete()
                            return AppResult.failure(
                                SubLearnError(SubLearnError.Kind.MalformedInput, "File is not a SQLite database"),
                            )
                        }
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                }
            }

            val sqliteSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expectedSha256.isNullOrBlank() && !isGzip && !sqliteSha256.equals(expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                return AppResult.failure(
                    SubLearnError(
                        SubLearnError.Kind.MalformedInput,
                        "SHA-256 mismatch: expected $expectedSha256, got $sqliteSha256",
                    ),
                )
            }

            // Validate v2 schema & read meta
            val metaMap = HashMap<String, String>()
            val db = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use { sqlite ->
                val tables = HashSet<String>()
                sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')", null).use { c ->
                    while (c.moveToNext()) tables += c.getString(0)
                }
                if ("meta" !in tables || "entry" !in tables || "sense" !in tables) {
                    tempFile.delete()
                    return AppResult.failure(
                        SubLearnError(SubLearnError.Kind.MalformedInput, "Not a v2 lexicon pack (missing meta/entry/sense)"),
                    )
                }
                sqlite.rawQuery("SELECT key, value FROM meta", null).use { c ->
                    while (c.moveToNext()) {
                        metaMap[c.getString(0)] = c.getString(1)
                    }
                }
            }

            val formatVer = metaMap["format_version"]?.toIntOrNull() ?: 0
            if (formatVer != 2) {
                tempFile.delete()
                return AppResult.failure(
                    SubLearnError(
                        SubLearnError.Kind.UnsupportedFormat,
                        "Unsupported lexicon pack format_version=$formatVer (expected 2)",
                    ),
                )
            }

            val direction = metaMap["direction"] ?: if (metaMap["source_lang"] == "fa") "fa-en" else "en-fa"
            val packKind = if (direction == "fa-en") PackKind.DICT_FA_EN else PackKind.LEXICON_EN_FA
            val packId = metaMap["pack_id"] ?: if (direction == "fa-en") "dict-fa-en-v1" else "lexicon-en-fa-v2"
            val finalName = if (direction == "fa-en") "sublearn-dict-fa-en-v1.sqlite" else "sublearn-lexicon-en-fa-v2.sqlite"
            val targetFile = File(packsDir, finalName)

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val entryCount = metaMap["entry_count"]?.toIntOrNull() ?: 0
            val senseCount = metaMap["sense_count"]?.toIntOrNull() ?: 0
            val patternCount = metaMap["pattern_count"]?.toIntOrNull() ?: 0
            val lemmaCount = metaMap["lemma_count"]?.toIntOrNull() ?: 0
            val builderVersion = metaMap["builder_version"] ?: "2.0.0"
            val builtAt = metaMap["built_at"] ?: ""
            val sourceLang = metaMap["source_lang"] ?: if (direction == "fa-en") "fa" else "en"
            val glossLang = metaMap["gloss_lang"] ?: if (direction == "fa-en") "en" else "fa"

            val entity = InstalledPackEntity(
                packId = packId,
                kind = packKind.id,
                version = builderVersion,
                titleEn = if (packKind == PackKind.DICT_FA_EN) {
                    "Persian-to-English Dictionary v1"
                } else {
                    "SubLearn Lexicon EN→FA v2"
                },
                titleFa = if (packKind == PackKind.DICT_FA_EN) {
                    "لغت‌نامهٔ فارسی به انگلیسی"
                } else {
                    "لغت‌نامهٔ جامع انگلیسی به فارسی (نسخهٔ ۲)"
                },
                localPath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
                sha256 = sqliteSha256,
                entryCount = entryCount,
                sourceLang = sourceLang,
                targetLang = glossLang,
                enabled = true,
                installedAt = System.currentTimeMillis(),
            )
            packRepository.register(entity)
            lexiconAccess.reloadFromRepository()

            val meta = LexiconV2Meta(
                formatVersion = formatVer,
                packId = packId,
                direction = direction,
                sourceLang = sourceLang,
                glossLang = glossLang,
                entryCount = entryCount,
                senseCount = senseCount,
                patternCount = patternCount,
                lemmaCount = lemmaCount,
                builtAt = builtAt,
                builderVersion = builderVersion,
            )
            return AppResult.success(
                LexiconImportResult(
                    success = true,
                    packId = packId,
                    kind = packKind,
                    meta = meta,
                ),
            )
        } catch (t: Throwable) {
            tempFile.delete()
            return AppResult.failure(
                SubLearnError(SubLearnError.Kind.MalformedInput, "Failed to import pack: ${t.message}", t),
            )
        }
    }
}
