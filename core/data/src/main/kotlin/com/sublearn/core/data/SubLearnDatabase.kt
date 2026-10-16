package com.sublearn.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecentVideoEntity::class,
        MyWordEntity::class,
        MyWordFts::class,
        TranslationCacheEntity::class,
        InstalledPackEntity::class,
        PhraseSpanCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SubLearnDatabase : RoomDatabase() {
    abstract fun recentVideos(): RecentVideoDao

    abstract fun myWords(): MyWordDao

    abstract fun translationCache(): TranslationCacheDao

    abstract fun installedPacks(): InstalledPackDao

    abstract fun phraseSpans(): PhraseSpanCacheDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `phraseType` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `lemma` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `lexiconEntryId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `surfaceParts` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `sourceCueEndMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `my_words` ADD COLUMN `sourceKind` TEXT DEFAULT NULL")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `installed_packs` (
                        `packId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `version` TEXT NOT NULL,
                        `titleEn` TEXT NOT NULL,
                        `titleFa` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `entryCount` INTEGER NOT NULL DEFAULT 0,
                        `sourceLang` TEXT NOT NULL DEFAULT 'en',
                        `targetLang` TEXT NOT NULL DEFAULT 'fa',
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `installedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`packId`)
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installed_packs_kind` ON `installed_packs` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installed_packs_enabled` ON `installed_packs` (`enabled`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `phrase_span` (
                        `sourceKey` TEXT NOT NULL,
                        `packId` TEXT NOT NULL,
                        `packVersion` TEXT NOT NULL,
                        `analyzerVersion` INTEGER NOT NULL,
                        `spanId` TEXT NOT NULL,
                        `blockId` INTEGER NOT NULL,
                        `startChar` INTEGER NOT NULL,
                        `endChar` INTEGER NOT NULL,
                        `tokensCsv` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `headword` TEXT NOT NULL,
                        `lemmaKey` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `separable` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceKey`, `packId`, `packVersion`, `analyzerVersion`, `spanId`)
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_phrase_span_sourceKey_blockId` " +
                        "ON `phrase_span` (`sourceKey`, `blockId`)",
                )
            }
        }

        private val migrations: Array<Migration> = arrayOf(MIGRATION_1_2)

        /**
         * Create the database. Intentionally does NOT call `fallbackToDestructiveMigration()`
         * (§2.5): if a migration fails, the app should surface the error and offer a backup
         * export rather than silently wiping all My Words data.
         *
         * The caller is expected to catch `IllegalStateException` / `SQLiteException` from
         * the first database access and show a recovery UI with an export option.
         */
        fun create(context: Context): SubLearnDatabase = Room.databaseBuilder(
            context.applicationContext,
            SubLearnDatabase::class.java,
            "sublearn.db",
        )
            .addMigrations(*migrations)
            .build()
    }
}
