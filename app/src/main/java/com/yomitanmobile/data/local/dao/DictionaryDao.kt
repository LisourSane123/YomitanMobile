package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.DictionaryEntry
import kotlinx.coroutines.flow.Flow

data class FrequencyUpdate(
    val expression: String,
    val reading: String?,
    val frequency: Int,
    // The label the source list ships (rank as a string, or a bucketed
    // label). Blank falls back to [frequency] at the storage layer.
    val displayValue: String = ""
)

data class JlptUpdate(
    val expression: String,
    val reading: String?,
    val level: Int
)

@Dao
interface DictionaryDao {

    /**
     * Exact-match lookup on expression OR reading (no prefix wildcards).
     * Used for deconjugation alternatives: a base form like 見る should
     * surface only itself, not every longer entry that starts with 見る
     * (which the prefix-LIKE [searchCombined] would drag in).
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE expression = :exactQuery OR reading = :exactQuery
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
                 frequency ASC,
                 LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchExact(exactQuery: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE reading = :reading ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC")
    suspend fun getByReading(reading: String): List<DictionaryEntry>

    /**
     * Two-parameter signature so the equality match (`= :exactQuery`) uses
     * the user's literal input while the prefix match (`LIKE :likeQuery ||
     * '%'`) receives a version with SQL LIKE wildcards (`%`, `_`, `\`)
     * pre-escaped via [com.yomitanmobile.util.InputSanitizer.sanitizeLikeQuery].
     * Without the escape, a user typing `%` matched everything; `_` matched
     * any single char. The ESCAPE '\' clause tells SQLite to honour the
     * backslash-escapes the sanitizer emits.
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE expression = :exactQuery
           OR reading = :exactQuery
           OR expression LIKE :likeQuery || '%' ESCAPE '\'
           OR reading LIKE :likeQuery || '%' ESCAPE '\'
        ORDER BY
            CASE
                WHEN expression = :exactQuery THEN 0
                WHEN reading = :exactQuery THEN 1
                ELSE 2
            END,
            CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
            frequency ASC,
            LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchCombined(exactQuery: String, likeQuery: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE id = :id")
    suspend fun getById(id: Long): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryEntry): Long

    @Query("DELETE FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun deleteByDictionary(dictionaryName: String)

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun getEntryCount(): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun getEntryCountForDictionary(dictionaryName: String): Int

    @Query("INSERT INTO dictionary_entries_fts(dictionary_entries_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()

    /**
     * Search by English definition text using FTS.
     * Matches words whose definition column contains the query string.
     */
    @Query("""
        SELECT dictionary_entries.* FROM dictionary_entries
        JOIN dictionary_entries_fts ON dictionary_entries.rowid = dictionary_entries_fts.rowid
        WHERE dictionary_entries_fts MATCH :query
        ORDER BY CASE WHEN dictionary_entries.frequency > 0 THEN 0 ELSE 1 END,
                 dictionary_entries.frequency ASC,
                 LENGTH(dictionary_entries.expression) ASC
        LIMIT :limit
    """)
    fun searchByDefinition(query: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query("UPDATE dictionary_entries SET dictionary_name = :newName WHERE dictionary_name = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND frequency = 0")
    suspend fun updateFrequency(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression")
    suspend fun updateFrequencyForce(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND reading = :reading")
    suspend fun updateFrequencyWithReading(expression: String, reading: String, frequency: Int)

    // "Best rank" variants: only lower an existing rank (or fill a 0), so the
    // dictionary_entries.frequency column reflects the most-frequent rank
    // across ALL installed lists rather than whichever list imported last.
    // Per-list ranks live in the word_frequencies table.
    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND (frequency = 0 OR frequency > :frequency)")
    suspend fun updateFrequencyBest(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND reading = :reading AND (frequency = 0 OR frequency > :frequency)")
    suspend fun updateFrequencyBestWithReading(expression: String, reading: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET pitch_accent = :pitchAccent WHERE expression = :expression AND (pitch_accent = '' OR pitch_accent IS NULL)")
    suspend fun updatePitchAccent(expression: String, pitchAccent: String)

    @Query("UPDATE dictionary_entries SET pitch_accent = :pitchAccent WHERE expression = :expression")
    suspend fun updatePitchAccentForce(expression: String, pitchAccent: String)

    @androidx.room.Transaction
    suspend fun updateFrequencyBatch(batch: List<FrequencyUpdate>) {
        for (update in batch) {
            val reading = update.reading?.trim().orEmpty()
            if (reading.isNotBlank()) {
                updateFrequencyBestWithReading(update.expression, reading, update.frequency)
            } else {
                updateFrequencyBest(update.expression, update.frequency)
            }
        }
    }

    @androidx.room.Transaction
    suspend fun updatePitchAccentBatch(batch: Map<String, String>) {
        for ((expression, pitchAccent) in batch) {
            updatePitchAccentForce(expression, pitchAccent)
        }
    }

    @Query("UPDATE dictionary_entries SET jlpt_level = :level WHERE expression = :expression AND reading = :reading AND jlpt_level = 0")
    suspend fun updateJlptLevelWithReading(expression: String, reading: String, level: Int)

    @Query("UPDATE dictionary_entries SET jlpt_level = :level WHERE expression = :expression AND jlpt_level = 0")
    suspend fun updateJlptLevelByExpression(expression: String, level: Int)

    @androidx.room.Transaction
    suspend fun updateJlptLevelBatch(batch: List<JlptUpdate>) {
        for (update in batch) {
            val reading = update.reading?.trim().orEmpty()
            if (reading.isNotBlank()) {
                updateJlptLevelWithReading(update.expression, reading, update.level)
            } else {
                updateJlptLevelByExpression(update.expression, update.level)
            }
        }
    }
}
