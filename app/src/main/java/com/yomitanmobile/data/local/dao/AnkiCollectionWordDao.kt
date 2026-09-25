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

    /** When the stored scan was taken; 0 when nothing is stored. */
    @Query("SELECT COALESCE(MAX(scanned_at), 0) FROM anki_collection_words")
    suspend fun lastScannedAt(): Long

    @Query("SELECT COUNT(*) FROM anki_collection_words")
    fun observeCount(): Flow<Int>

    @Query("SELECT word FROM anki_collection_words")
    suspend fun getAllWords(): List<String>

    @Query("SELECT word FROM anki_collection_words WHERE mature = 1")
    suspend fun getMatureWords(): List<String>

    /** Words on a card that is neither new nor suspended; see the entity. */
    @Query("SELECT word FROM anki_collection_words WHERE studied = 1")
    suspend fun getStudiedWords(): List<String>

    @Query("SELECT COUNT(*) FROM anki_collection_words WHERE studied = 1")
    suspend fun studiedCount(): Int

    @Query("SELECT COUNT(*) FROM anki_collection_words WHERE mature = 1")
    suspend fun matureCount(): Int

    @Query("SELECT * FROM anki_collection_words ORDER BY source, word LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<AnkiCollectionWord>

    /**
     * Diagnostic lookup for the scan screen.
     *
     * Matches anywhere in the word, not just at the front: the question being
     * asked is "did the scan pick up 来る?", and a compound like 持って来る is
     * exactly the case that a prefix match hides. Prefix hits still sort first.
     *
     * `ESCAPE` takes a SINGLE character — the two-character `'\\'` that used to
     * stand here made SQLite reject the statement, and the caller turned the
     * exception into an empty list, so the search box answered every query with
     * "nothing found".
     */
    @Query(
        """
        SELECT * FROM anki_collection_words
        WHERE word LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY (word LIKE :query || '%' ESCAPE '\') DESC, word
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
