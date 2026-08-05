package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yomitanmobile.data.local.entity.AnkiCollectionWord
import kotlinx.coroutines.flow.Flow

/** Words per note type, for the scan screen's breakdown. */
data class AnkiSourceCount(val source: String, val wordCount: Int)

/** Room binds one statement per row; chunking keeps each batch sane. */
private const val INSERT_CHUNK = 2000

/**
 * Stored result of the AnkiDroid collection scan. See [AnkiCollectionWord] for
 * why the scan is persisted rather than repeated.
 */
@Dao
interface AnkiCollectionWordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AnkiCollectionWord>)

    @Query("DELETE FROM anki_collection_words")
    suspend fun deleteAll()

    /**
     * Swaps in a fresh scan. Wrapped in one transaction so a crash mid-write
     * can't leave the app believing the collection is half empty — which would
     * silently re-create cards the user already has.
     */
    @Transaction
    suspend fun replaceAll(rows: List<AnkiCollectionWord>) {
        deleteAll()
        rows.chunked(INSERT_CHUNK).forEach { insertAll(it) }
    }

    @Query("SELECT COUNT(*) FROM anki_collection_words")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM anki_collection_words")
    fun observeCount(): Flow<Int>

    @Query("SELECT word FROM anki_collection_words")
    suspend fun getAllWords(): List<String>

    @Query("SELECT * FROM anki_collection_words ORDER BY source, word LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<AnkiCollectionWord>

    @Query(
        """
        SELECT * FROM anki_collection_words
        WHERE word LIKE :query || '%'
        ORDER BY word
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<AnkiCollectionWord>

    @Query(
        """
        SELECT source AS source, COUNT(*) AS wordCount
        FROM anki_collection_words
        GROUP BY source
        ORDER BY wordCount DESC
        """
    )
    suspend fun countsBySource(): List<AnkiSourceCount>
}
