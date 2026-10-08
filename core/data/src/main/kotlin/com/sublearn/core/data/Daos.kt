package com.sublearn.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentVideoDao {
    @Query("SELECT * FROM recent_videos ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 40): Flow<List<RecentVideoEntity>>

    @Query("SELECT * FROM recent_videos WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): RecentVideoEntity?

    @Upsert
    suspend fun upsert(entity: RecentVideoEntity)

    @Query("DELETE FROM recent_videos WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_videos")
    suspend fun clear()

    @Query("UPDATE recent_videos SET lastPositionMs = :positionMs WHERE uri = :uri")
    suspend fun savePosition(uri: String, positionMs: Long)

    @Query("SELECT COUNT(*) FROM recent_videos")
    fun observeCount(): Flow<Int>
}

@Dao
interface MyWordDao {
    @Query("SELECT * FROM my_words ORDER BY markedAt DESC")
    fun observeAll(): Flow<List<MyWordEntity>>

    @Query("SELECT * FROM my_words ORDER BY markedAt DESC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): Flow<List<MyWordEntity>>

    @Query("SELECT * FROM my_words WHERE wordLower IN (:wordsLower)")
    suspend fun findByWords(wordsLower: List<String>): List<MyWordEntity>

    @Query("SELECT wordLower FROM my_words WHERE status = :status")
    suspend fun wordsWithStatus(status: String): List<String>

    @Query("SELECT COUNT(*) FROM my_words")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(entity: MyWordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MyWordEntity): Long

    @Delete
    suspend fun delete(entity: MyWordEntity)

    @Query("DELETE FROM my_words WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE my_words SET status = :status, reviewedAt = :now, reviewCount = reviewCount + 1 WHERE id = :id")
    suspend fun markStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE my_words SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Query("UPDATE my_words SET translation = :translation WHERE id = :id")
    suspend fun updateTranslation(id: Long, translation: String?)

    /**
     * FTS search; [searchLike] is the fallback when the query has no FTS-safe tokens.
     *
     * Ordered by recency rather than by the FTS `rank` column: `rank` is a hidden column of the
     * external-content table and is not visible through this join, and the most recently marked word
     * is what a learner wants at the top anyway.
     */
    @Query(
        "SELECT m.* FROM my_words_fts JOIN my_words m ON m.rowid = my_words_fts.rowid " +
            "WHERE my_words_fts MATCH :query ORDER BY m.markedAt DESC LIMIT :limit",
    )
    fun searchFts(query: String, limit: Int = 200): Flow<List<MyWordEntity>>

    @Query("SELECT * FROM my_words WHERE wordLower LIKE '%' || :query || '%' ORDER BY markedAt DESC LIMIT :limit")
    fun searchLike(query: String, limit: Int = 200): Flow<List<MyWordEntity>>
}

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translation_cache WHERE cacheKey = :key")
    suspend fun find(key: String): TranslationCacheEntity?

    @Query("SELECT * FROM translation_cache WHERE cacheKey IN (:keys)")
    suspend fun findAll(keys: List<String>): List<TranslationCacheEntity>

    @Upsert
    suspend fun put(entity: TranslationCacheEntity)

    @Query("UPDATE translation_cache SET hits = hits + 1 WHERE cacheKey = :key")
    suspend fun bump(key: String)

    @Query("DELETE FROM translation_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int

    /** Keeps the cache bounded without a scheduled job: evict the least valuable rows first. */
    @Query(
        "DELETE FROM translation_cache WHERE cacheKey IN (" +
            "SELECT cacheKey FROM translation_cache ORDER BY (hits + 1) * 1.0 / MAX(1, (:now - createdAt) / 86400000.0) ASC " +
            "LIMIT :count)",
    )
    suspend fun evictLeastValuable(now: Long, count: Int)
}
