package com.sublearn.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        RecentVideoEntity::class,
        MyWordEntity::class,
        MyWordFts::class,
        TranslationCacheEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SubLearnDatabase : RoomDatabase() {
    abstract fun recentVideos(): RecentVideoDao

    abstract fun myWords(): MyWordDao

    abstract fun translationCache(): TranslationCacheDao

    companion object {
        /**
         * Empty today: version 1 is the first shipped schema. Every later change adds a numbered
         * step here (and a schema export) instead of leaning on the destructive fallback.
         */
        private val migrations: Array<Migration> = emptyArray()

        fun create(context: Context): SubLearnDatabase = Room.databaseBuilder(
            context.applicationContext,
            SubLearnDatabase::class.java,
            "sublearn.db",
        )
            // The app ships its first schema; user data is recreatable except My Words, which is
            // exported by Settings → Export. Destructive fallback only applies pre-1.0 (docs/PHASES.md).
            .fallbackToDestructiveMigration()
            .addMigrations(*migrations)
            .build()
    }
}
