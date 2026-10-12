package com.sublearn.core.lexicon

import android.database.sqlite.SQLiteDatabase
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.data.WordKey
import com.sublearn.core.nlp.Lemmatiser
import com.sublearn.core.nlp.LexiconAccess
import com.sublearn.core.nlp.LexiconEntry
import com.sublearn.core.nlp.LexiconPattern
import com.sublearn.core.nlp.PhraseKind
import com.sublearn.core.nlp.SenseCandidate
import com.sublearn.core.settings.CefrLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite-backed implementation of [LexiconAccess] over installed packs (`LEXICON_EN_FA`,
 * `DICT_FA_EN`, and `LEGACY_V1`), with chunked batch queries (`<= 500` bind parameters)
 * and in-memory lemma/anchor caches for `< 50 ms` lookup latency (Phase 10, D-43, D-58).
 */
class PackLexiconAccess(
    private val packRepository: InstalledPackRepository,
) : LexiconAccess {

    @Volatile
    private var enFaDb: SQLiteDatabase? = null

    @Volatile
    private var faEnDb: SQLiteDatabase? = null

    @Volatile
    private var legacyV1Db: SQLiteDatabase? = null

    @Volatile
    private var activePackIdValue: String = "none"

    @Volatile
    private var activePackVersionValue: String = "0"

    @Volatile
    private var lemmatiserInstance: Lemmatiser = Lemmatiser()

    private val anchorCache = ConcurrentHashMap<String, List<LexiconEntry>>()
    private val wordPosCache = ConcurrentHashMap<String, Map<String, Int>>()

    fun activePackId(): String = activePackIdValue

    fun activePackVersion(): String = activePackVersionValue

    fun hasActiveEnFaPack(): Boolean = enFaDb?.isOpen == true

    fun hasActiveFaEnPack(): Boolean = faEnDb?.isOpen == true

    fun hasActiveLegacyV1Pack(): Boolean = legacyV1Db?.isOpen == true

    suspend fun reloadFromRepository() = withContext(Dispatchers.IO) {
        val enFaEntity = packRepository.findActiveByKind(PackKind.LEXICON_EN_FA.id)
        val faEnEntity = packRepository.findActiveByKind(PackKind.DICT_FA_EN.id)
        val legacyEntity = packRepository.findActiveByKind(PackKind.LEGACY_V1.id)

        enFaDb?.close()
        faEnDb?.close()
        legacyV1Db?.close()
        anchorCache.clear()
        wordPosCache.clear()

        enFaDb = openReadOnlyIfValid(enFaEntity?.localPath)
        faEnDb = openReadOnlyIfValid(faEnEntity?.localPath)
        legacyV1Db = openReadOnlyIfValid(legacyEntity?.localPath)

        activePackIdValue = enFaEntity?.packId ?: legacyEntity?.packId ?: "none"
        activePackVersionValue = enFaEntity?.version ?: legacyEntity?.version ?: "0"

        val db = enFaDb
        if (db != null) {
            val forms = ArrayList<Triple<String, String, String?>>()
            runCatching {
                db.rawQuery("SELECT form, lemma, pos FROM lemma_form ORDER BY rank ASC, lemma ASC", null).use { c ->
                    while (c.moveToNext()) {
                        forms += Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))
                    }
                }
            }
            lemmatiserInstance = Lemmatiser.fromLexicon(forms)
        } else {
            lemmatiserInstance = Lemmatiser()
        }
    }

    private fun openReadOnlyIfValid(path: String?): SQLiteDatabase? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()
    }

    override fun lemmatiser(form: String): Set<String> = lemmatiserInstance.candidates(form)

    override fun findByAnchor(anchorLemma: String): List<LexiconEntry> {
        val norm = anchorLemma.lowercase().trim()
        if (norm.isEmpty()) return emptyList()
        anchorCache[norm]?.let { return it }
        val loaded = findByAnchorBatch(listOf(norm))[norm].orEmpty()
        anchorCache[norm] = loaded
        return loaded
    }

    /**
     * Batch-loads anchor patterns in chunks of `<= 500` bind parameters so SQLite never hits the
     * 999 bind parameter limit on long subtitle files.
     */
    fun findByAnchorBatch(anchorLemmas: Collection<String>): Map<String, List<LexiconEntry>> {
        val db = enFaDb?.takeIf { it.isOpen } ?: return emptyMap()
        val distinct = anchorLemmas.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return emptyMap()

        val result = HashMap<String, MutableList<LexiconEntry>>()
        val missing = ArrayList<String>()
        for (lem in distinct) {
            val cached = anchorCache[lem]
            if (cached != null) {
                result[lem] = cached.toMutableList()
            } else {
                missing += lem
            }
        }
        if (missing.isEmpty()) return result

        for (chunk in missing.chunked(MAX_SQL_BIND_PARAMS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = """
                SELECT p.anchor_lemma, e.id, e.key, e.headword, e.kind, e.pos, e.cefr,
                       p.pattern, p.separable, p.min_tokens, p.max_tokens
                FROM pattern p
                JOIN entry e ON e.id = p.entry_id
                WHERE p.anchor_lemma IN ($placeholders)
                ORDER BY e.id, p.id
            """.trimIndent()

            val byAnchorAndEntry = HashMap<Pair<String, Long>, Pair<LexiconEntry, MutableList<LexiconPattern>>>()
            runCatching {
                db.rawQuery(sql, chunk.toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        val anchor = c.getString(0)
                        val entryId = c.getLong(1)
                        val key = c.getString(2)
                        val headword = c.getString(3)
                        val kindStr = c.getString(4)
                        val pos = if (c.isNull(5)) null else c.getString(5)
                        val cefr = if (c.isNull(6)) null else c.getString(6)
                        val patStr = c.getString(7)
                        val sep = c.getInt(8) != 0
                        val minTok = c.getInt(9)
                        val maxTok = c.getInt(10)

                        val phraseKind = when (kindStr) {
                            "PHRASAL" -> PhraseKind.PHRASAL
                            "IDIOM" -> PhraseKind.IDIOM
                            "COLLOCATION" -> PhraseKind.COLLOCATION
                            else -> null
                        }
                        val pairKey = anchor to entryId
                        val holder = byAnchorAndEntry.getOrPut(pairKey) {
                            LexiconEntry(
                                id = entryId,
                                key = key,
                                headword = headword,
                                kind = phraseKind,
                                pos = pos,
                                cefr = cefr,
                            ) to ArrayList()
                        }
                        holder.second += LexiconPattern(
                            entryId = entryId,
                            pattern = patStr,
                            anchorLemma = anchor,
                            separable = sep,
                            minTokens = minTok,
                            maxTokens = maxTok,
                        )
                    }
                }
            }

            val groupedByAnchor = HashMap<String, MutableList<LexiconEntry>>()
            for ((pairKey, value) in byAnchorAndEntry) {
                val anchor = pairKey.first
                val entryWithPats = value.first.copy(patterns = value.second)
                groupedByAnchor.getOrPut(anchor) { ArrayList() } += entryWithPats
            }
            for (lem in chunk) {
                val list = groupedByAnchor[lem] ?: emptyList()
                anchorCache[lem] = list
                result[lem] = list.toMutableList()
            }
        }
        return result
    }

    /** Batch-loads `word_pos(word, pos, freq)` for [LexiconPosTagger] in chunks of `<= 500`. */
    fun wordPosFrequencies(words: Collection<String>): Map<String, Map<String, Int>> {
        val db = enFaDb?.takeIf { it.isOpen } ?: return emptyMap()
        val distinct = words.map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return emptyMap()

        val result = HashMap<String, Map<String, Int>>()
        val missing = ArrayList<String>()
        for (w in distinct) {
            val cached = wordPosCache[w]
            if (cached != null) {
                result[w] = cached
            } else {
                missing += w
            }
        }
        if (missing.isEmpty()) return result

        for (chunk in missing.chunked(MAX_SQL_BIND_PARAMS)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val temp = HashMap<String, MutableMap<String, Int>>()
            runCatching {
                db.rawQuery(
                    "SELECT word, pos, freq FROM word_pos WHERE word IN ($placeholders)",
                    chunk.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val w = c.getString(0)
                        val p = c.getString(1)
                        val f = c.getInt(2)
                        temp.getOrPut(w) { HashMap() }[p] = f
                    }
                }
            }
            for (w in chunk) {
                val map = temp[w] ?: emptyMap()
                wordPosCache[w] = map
                result[w] = map
            }
        }
        return result
    }

    override fun lookupWord(key: String): LexiconEntry? {
        val db = enFaDb?.takeIf { it.isOpen } ?: return null
        val norm = WordKey.normalize(key)
        if (norm.isEmpty()) return null
        return runCatching {
            db.rawQuery(
                "SELECT id, key, headword, kind, pos, cefr FROM entry WHERE key = ? LIMIT 1",
                arrayOf(norm),
            ).use { c ->
                if (!c.moveToFirst()) return@use null
                val kindStr = c.getString(3)
                val pKind = when (kindStr) {
                    "PHRASAL" -> PhraseKind.PHRASAL
                    "IDIOM" -> PhraseKind.IDIOM
                    "COLLOCATION" -> PhraseKind.COLLOCATION
                    else -> null
                }
                LexiconEntry(
                    id = c.getLong(0),
                    key = c.getString(1),
                    headword = c.getString(2),
                    kind = pKind,
                    pos = if (c.isNull(4)) null else c.getString(4),
                    cefr = if (c.isNull(5)) null else c.getString(5),
                )
            }
        }.getOrNull()
    }

    override fun senses(entryId: Long): List<SenseCandidate> {
        val db = enFaDb?.takeIf { it.isOpen } ?: return emptyList()
        val out = ArrayList<SenseCandidate>()
        runCatching {
            db.rawQuery(
                """SELECT id, gloss_fa, gloss_en, pos, COALESCE(domain_fa, domain), register, is_primary, cefr
                   FROM sense WHERE entry_id = ? ORDER BY position ASC, id ASC""",
                arrayOf(entryId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val sId = c.getLong(0)
                    val exs = examples(sId).map { it.first }
                    out += SenseCandidate(
                        senseId = sId,
                        glossFa = c.getString(1),
                        glossEn = if (c.isNull(2)) null else c.getString(2),
                        pos = if (c.isNull(3)) null else c.getString(3),
                        domain = if (c.isNull(4)) null else c.getString(4),
                        register = if (c.isNull(5)) null else c.getString(5),
                        isPrimary = c.getInt(6) != 0,
                        examples = exs,
                        cefr = if (c.isNull(7)) null else c.getString(7),
                    )
                }
            }
        }
        return out
    }

    override fun examples(senseId: Long): List<Pair<String, String>> {
        val db = enFaDb?.takeIf { it.isOpen } ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            db.rawQuery(
                "SELECT COALESCE(en, ''), COALESCE(fa, '') FROM example WHERE sense_id = ? ORDER BY id ASC",
                arrayOf(senseId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += c.getString(0) to c.getString(1)
                }
            }
        }
        return out
    }

    /** Full dictionary entry lookup in `LEXICON_EN_FA` (with lemma fallback for inflected words). */
    fun lookupFullEntry(wordOrPhrase: String): DictionaryEntry? {
        val db = enFaDb?.takeIf { it.isOpen } ?: return null
        val norm = WordKey.normalize(wordOrPhrase)
        if (norm.isEmpty()) return null

        val candidateKeys = LinkedHashSet<String>()
        candidateKeys += norm
        if (' ' !in norm) {
            candidateKeys.addAll(lemmatiser(norm))
        }

        for (cand in candidateKeys) {
            val found = readV2EntryByKey(db, cand, sourceLabel = "Lexicon v2")
            if (found != null) return found
        }
        return null
    }

    /** Reverse Persian-to-English lookup in `DICT_FA_EN` (or fallback gloss search in `LEXICON_EN_FA`). */
    fun lookupReverseFaEn(persianQuery: String): DictionaryEntry? {
        val norm = WordKey.normalize(persianQuery)
        if (norm.isEmpty()) return null

        val faDb = faEnDb?.takeIf { it.isOpen }
        if (faDb != null) {
            readV2EntryByKey(faDb, norm, sourceLabel = "FA→EN Pack")?.let { return it }
        }

        // Fallback: search Persian glosses in EN->FA pack
        val enDb = enFaDb?.takeIf { it.isOpen } ?: return null
        return runCatching {
            val englishMatches = ArrayList<String>()
            enDb.rawQuery(
                """SELECT DISTINCT e.headword, s.gloss_fa
                   FROM sense s JOIN entry e ON e.id = s.entry_id
                   WHERE s.gloss_fa LIKE ?
                   ORDER BY s.is_primary DESC, e.prior DESC LIMIT 12""",
                arrayOf("%$norm%"),
            ).use { c ->
                while (c.moveToNext()) {
                    englishMatches += "${c.getString(0)} — ${c.getString(1)}"
                }
            }
            if (englishMatches.isEmpty()) {
                null
            } else {
                DictionaryEntry(
                    headword = persianQuery.trim(),
                    meanings = englishMatches,
                    sourceLabel = "Lexicon v2 (Reverse)",
                )
            }
        }.getOrNull()
    }

    /** Lookup in a Legacy v1 (`main_db.sqlite` / `.bipe`) database (D-61). */
    fun lookupLegacyV1(wordOrPhrase: String): DictionaryEntry? {
        val db = legacyV1Db?.takeIf { it.isOpen } ?: return null
        val norm = WordKey.normalize(wordOrPhrase)
        if (norm.isEmpty()) return null

        return runCatching {
            var wordId: Long? = null
            var headword: String = norm
            db.rawQuery(
                "SELECT english_word_id, english_word FROM english_words WHERE LOWER(english_word) = ? LIMIT 1",
                arrayOf(norm),
            ).use { c ->
                if (c.moveToFirst()) {
                    wordId = c.getLong(0)
                    headword = c.getString(1)
                }
            }
            val wid = wordId ?: return@runCatching null

            val meanings = ArrayList<String>()
            var cefrStr: String? = null
            val detailIds = ArrayList<Long>()
            db.rawQuery(
                """SELECT english_detail_id, persian_meaning, cefr
                   FROM english_details WHERE english_word_id = ? ORDER BY position ASC""",
                arrayOf(wid.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val did = c.getLong(0)
                    val m = c.getString(1)?.trim()
                    val lv = if (c.isNull(2)) null else c.getString(2)?.trim()
                    if (!m.isNullOrEmpty()) {
                        meanings += m
                        detailIds += did
                    }
                    if (cefrStr == null && !lv.isNullOrEmpty()) cefrStr = lv
                }
            }
            if (meanings.isEmpty()) return@runCatching null

            val examples = ArrayList<Pair<String, String>>()
            for (did in detailIds.take(5)) {
                db.rawQuery(
                    """SELECT COALESCE(english_sentence, ''), COALESCE(persian_sentence, '')
                       FROM english_sentences WHERE english_detail_id = ? LIMIT 3""",
                    arrayOf(did.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        examples += c.getString(0) to c.getString(1)
                    }
                }
            }

            var ipaUs: String? = null
            runCatching {
                db.rawQuery(
                    "SELECT phonetic FROM english_american_audios WHERE english_word_id = ? LIMIT 1",
                    arrayOf(wid.toString()),
                ).use { c ->
                    if (c.moveToFirst()) ipaUs = c.getString(0)
                }
            }

            val cefr = cefrStr?.let { lv -> CefrLevel.entries.firstOrNull { it.name.equals(lv, ignoreCase = true) } }
            DictionaryEntry(
                headword = headword,
                meanings = meanings,
                cefr = cefr,
                pronunciationUs = ipaUs,
                examples = examples,
                sourceLabel = "FastDic v1",
            )
        }.getOrNull()
    }

    /** Prefix / FTS4 search across English or Persian entries for the Dictionary screen. */
    fun searchDictionary(query: String, limit: Int = 40): List<DictionaryEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val isPersian = trimmed.any { it in '\u0600'..'\u06FF' }

        if (isPersian) {
            val faDb = faEnDb?.takeIf { it.isOpen }
            if (faDb != null) {
                val results = searchV2Db(faDb, WordKey.normalize(trimmed), limit, "FA→EN Pack")
                if (results.isNotEmpty()) return results
            }
            return listOfNotNull(lookupReverseFaEn(trimmed))
        }

        val enDb = enFaDb?.takeIf { it.isOpen }
        if (enDb != null) {
            return searchV2Db(enDb, WordKey.normalize(trimmed), limit, "Lexicon v2")
        }

        val legDb = legacyV1Db?.takeIf { it.isOpen }
        if (legDb != null) {
            return listOfNotNull(lookupLegacyV1(trimmed))
        }
        return emptyList()
    }

    private fun searchV2Db(db: SQLiteDatabase, normQuery: String, limit: Int, sourceLabel: String): List<DictionaryEntry> {
        val entryKeys = LinkedHashSet<String>()
        runCatching {
            db.rawQuery(
                "SELECT key FROM entry WHERE key = ? OR key LIKE ? ORDER BY LENGTH(key) ASC LIMIT ?",
                arrayOf(normQuery, "$normQuery%", limit.toString()),
            ).use { c ->
                while (c.moveToNext()) entryKeys += c.getString(0)
            }
        }
        if (entryKeys.size < limit) {
            val fts = WordKey.ftsQuery(normQuery)
            if (fts != null) {
                runCatching {
                    db.rawQuery(
                        "SELECT key FROM entry_fts WHERE entry_fts MATCH ? LIMIT ?",
                        arrayOf(fts, limit.toString()),
                    ).use { c ->
                        while (c.moveToNext()) entryKeys += c.getString(0)
                    }
                }
            }
        }
        return entryKeys.take(limit).mapNotNull { key -> readV2EntryByKey(db, key, sourceLabel) }
    }

    private fun readV2EntryByKey(db: SQLiteDatabase, key: String, sourceLabel: String): DictionaryEntry? = runCatching {
        var entryId = -1L
        var headword = key
        var kindStr = "WORD"
        var pos: String? = null
        var cefrStr: String? = null
        var register: String? = null

        db.rawQuery(
            "SELECT id, headword, kind, pos, cefr, register FROM entry WHERE key = ? LIMIT 1",
            arrayOf(key),
        ).use { c ->
            if (!c.moveToFirst()) return@runCatching null
            entryId = c.getLong(0)
            headword = c.getString(1)
            kindStr = c.getString(2)
            pos = if (c.isNull(3)) null else c.getString(3)
            cefrStr = if (c.isNull(4)) null else c.getString(4)
            register = if (c.isNull(5)) null else c.getString(5)
        }

        val meanings = ArrayList<String>()
        val englishGlosses = ArrayList<String>()
        val categories = LinkedHashSet<String>()
        val senseIds = ArrayList<Long>()

        db.rawQuery(
            """SELECT id, gloss_fa, gloss_en, COALESCE(domain_fa, domain), domain_en, pos, cefr
               FROM sense WHERE entry_id = ? ORDER BY position ASC, id ASC""",
            arrayOf(entryId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val sid = c.getLong(0)
                val gFa = c.getString(1)?.trim().orEmpty()
                val gEn = if (c.isNull(2)) null else c.getString(2)?.trim()
                val domFa = if (c.isNull(3)) null else c.getString(3)?.trim()
                val domEn = if (c.isNull(4)) null else c.getString(4)?.trim()
                if (gFa.isNotEmpty()) {
                    meanings += gFa
                    senseIds += sid
                }
                if (!gEn.isNullOrEmpty() && gEn !in englishGlosses) {
                    englishGlosses += gEn
                }
                if (!domFa.isNullOrEmpty()) categories += domFa
                if (!domEn.isNullOrEmpty()) categories += domEn
                if (pos == null && !c.isNull(5)) pos = c.getString(5)
                if (cefrStr == null && !c.isNull(6)) cefrStr = c.getString(6)
            }
        }

        val examples = ArrayList<Pair<String, String>>()
        for (sid in senseIds.take(6)) {
            db.rawQuery(
                "SELECT COALESCE(en, ''), COALESCE(fa, '') FROM example WHERE sense_id = ? LIMIT 3",
                arrayOf(sid.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val enEx = c.getString(0)
                    val faEx = c.getString(1)
                    if (enEx.isNotEmpty() || faEx.isNotEmpty()) {
                        examples += enEx to faEx
                    }
                }
            }
        }

        var synonyms = emptyList<String>()
        var antonyms = emptyList<String>()
        var wordFamily = emptyList<String>()
        runCatching {
            db.rawQuery(
                "SELECT synonyms, antonyms, word_family FROM entry_extra WHERE entry_id = ? LIMIT 1",
                arrayOf(entryId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) synonyms = c.getString(0).split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
                    if (!c.isNull(1)) antonyms = c.getString(1).split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
                    if (!c.isNull(2)) wordFamily = c.getString(2).split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        }

        var ipaUs: String? = null
        var ipaUk: String? = null
        runCatching {
            db.rawQuery(
                "SELECT ipa_us, ipa_uk FROM pron WHERE entry_id = ? LIMIT 1",
                arrayOf(entryId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) ipaUs = c.getString(0)
                    if (!c.isNull(1)) ipaUk = c.getString(1)
                }
            }
        }

        val phrasalVerbs = ArrayList<String>()
        val idioms = ArrayList<String>()
        val collocations = ArrayList<String>()
        runCatching {
            db.rawQuery(
                """SELECT ce.headword, ce.kind
                   FROM colloc col
                   JOIN entry ce ON ce.id = col.colloc_entry_id
                   WHERE col.entry_id = ?
                   ORDER BY col.position ASC LIMIT 24""",
                arrayOf(entryId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val hw = c.getString(0)
                    when (c.getString(1)) {
                        "PHRASAL" -> phrasalVerbs += hw
                        "IDIOM" -> idioms += hw
                        else -> collocations += hw
                    }
                }
            }
        }

        val cefr = cefrStr?.let { lv -> CefrLevel.entries.firstOrNull { it.name.equals(lv, ignoreCase = true) } }
        DictionaryEntry(
            headword = headword,
            meanings = meanings,
            synonyms = synonyms,
            antonyms = antonyms,
            phrasalVerbs = phrasalVerbs,
            collocations = collocations,
            idioms = idioms,
            wordFamily = wordFamily,
            cefr = cefr,
            categories = categories.toList(),
            pronunciationUs = ipaUs,
            pronunciationUk = ipaUk,
            pos = pos,
            register = register,
            englishGlosses = englishGlosses,
            examples = examples,
            kind = LexiconKind.fromKey(kindStr),
            entryId = entryId,
            sourceLabel = sourceLabel,
        )
    }.getOrNull()

    companion object {
        const val MAX_SQL_BIND_PARAMS = 500
    }
}
