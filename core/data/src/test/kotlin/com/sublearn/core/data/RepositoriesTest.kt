package com.sublearn.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoriesTest {
    @Test
    fun `word key normalises case punctuation and curly quotes`() {
        assertEquals("don't", WordKey.normalize("Don't"))
        assertEquals("don't", WordKey.normalize(" don\u2019t "))
        assertEquals("hello", WordKey.normalize("hello!"))
        assertEquals("سلام", WordKey.normalize("  سلام. "))
    }

    @Test
    fun `persian half space is treated as a separator so both spellings match`() {
        assertEquals(WordKey.normalize("می‌رم"), WordKey.normalize("می رم"))
    }

    @Test
    fun `punctuation-only tokens are not markable`() {
        assertTrue(!WordKey.isMarkable("..."))
        assertTrue(WordKey.isMarkable("word"))
        assertTrue(WordKey.isMarkable("۳"))
    }

    @Test
    fun `fts query quotes tokens and drops fts syntax`() {
        assertEquals("\"mak*\"", WordKey.ftsQuery("mak"))
        assertEquals("\"and*\" \"near*\"", WordKey.ftsQuery("and near"))
        assertEquals("\"NOT*\"", WordKey.ftsQuery("NOT"))
        assertEquals(null, WordKey.ftsQuery("   "))
    }

    @Test
    fun `translation key depends on text languages and provider only`() {
        val a = TranslationKey.of("Hello world", "en", "fa", "mlkit")
        val b = TranslationKey.of("  hello   WORLD ", "en", "fa", "mlkit")
        assertEquals(a, b)
        assertNotEquals(a, TranslationKey.of("Hello world", "en", "ar", "mlkit"))
        assertNotEquals(a, TranslationKey.of("Hello world", "en", "fa", "gemini"))
    }

    @Test
    fun `entity and domain round trip keeps semantics`() {
        val domain = MarkedWord(
            id = 3,
            word = "Give up",
            isPhrase = true,
            translation = "دست کشیدن",
            contextText = "I gave up.",
            contextTranslation = null,
            sourceTitle = "Movie",
            sourceStartMs = 42_000,
            status = WordStatus.LEARNING,
            level = "B2",
            note = "phrasal",
            markedAt = 10L,
            reviewCount = 2,
        )
        val entity = domain.toEntity()
        assertEquals(MyWordEntity.KIND_MULTI_WORD, entity.kind)
        assertEquals("give up", entity.wordLower)
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `status parsing is total`() {
        assertEquals(WordStatus.KNOWN, WordStatus.fromKey("KNOWN"))
        assertEquals(WordStatus.NEW, WordStatus.fromKey("nonsense"))
        assertEquals(WordStatus.NEW, WordStatus.fromKey(null))
    }

    @Test
    fun `v2 phrase fields and exporter round trip`() {
        val domain = MarkedWord(
            id = 7,
            word = "picked the book up",
            isPhrase = true,
            translation = "بلند کردن، برداشتن",
            contextText = "She picked the book up.",
            contextTranslation = null,
            sourceTitle = "Episode 1",
            sourceStartMs = 12_000,
            status = WordStatus.LEARNING,
            level = "B1",
            note = "separable phrasal",
            markedAt = 100L,
            reviewCount = 1,
            phraseType = "PHRASAL",
            lemma = "pick up",
            lexiconEntryId = 42L,
            surfaceParts = "picked ... up",
            sourceCueEndMs = 15_000L,
            sourceKind = "PACK",
        )
        assertEquals(domain, domain.toEntity().toDomain())

        val jsonText = MyWordsExporter.export(listOf(domain), WordsExportKind.JSON)
        val fromJson = MyWordsExporter.importText(jsonText)
        assertEquals(1, fromJson.size)
        assertEquals("pick up", fromJson[0].lemma)
        assertEquals("PHRASAL", fromJson[0].phraseType)

        val csvText = MyWordsExporter.export(listOf(domain), WordsExportKind.CSV)
        val fromCsv = MyWordsExporter.importText(csvText)
        assertEquals(1, fromCsv.size)
        assertEquals("PHRASAL", fromCsv[0].phraseType)
        assertEquals("pick up", fromCsv[0].lemma)

        val tsvText = MyWordsExporter.export(listOf(domain), WordsExportKind.ANKI_TSV)
        val fromTsv = MyWordsExporter.importText(tsvText)
        assertEquals(1, fromTsv.size)
        assertEquals("picked the book up", fromTsv[0].word)
    }

    @Test
    fun `room v1 to v2 migration executes all expected schema statements`() {
        val executed = ArrayList<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executed += args[0].toString()
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        assertEquals(1, SubLearnDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, SubLearnDatabase.MIGRATION_1_2.endVersion)
        SubLearnDatabase.MIGRATION_1_2.migrate(db)

        assertTrue(executed.any { it.contains("ADD COLUMN `phraseType`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `lemma`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `lexiconEntryId`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `surfaceParts`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `sourceCueEndMs`") })
        assertTrue(executed.any { it.contains("ADD COLUMN `sourceKind`") })
        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `installed_packs`") })
        assertTrue(executed.any { it.contains("CREATE TABLE IF NOT EXISTS `phrase_span_cache`") })
    }
}
