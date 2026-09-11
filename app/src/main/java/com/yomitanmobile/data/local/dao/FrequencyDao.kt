package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.WordFrequency
import kotlinx.coroutines.flow.Flow

/**
 * Access to the per-source [WordFrequency] table. See the entity for the
 * temp→real dictionary-name rename pattern used during import.
 */
@Dao
interface FrequencyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<WordFrequency>)

    /**
     * All installed lists' ranks for a word. Matches the exact reading plus
     * the empty-reading fallback (lists that shipped no reading).
     */
    @Query(
        """
        SELECT * FROM word_frequencies
        WHERE expression = :expression AND (reading = :reading OR reading = '')
        ORDER BY rank ASC
        """
    )
    suspend fun getForWord(expression: String, reading: String): List<WordFrequency>

    /**
     * Every spelling and reading the installed frequency lists rank inside
     * [maxRank] — the "this is a form people actually use" set.
     *
     * The text scanner needs it to choose between two readings of the same
     * stretch of text: JMdict lists 今日は (the greeting こんにちは) and 急いで
     * as entries of their own, and longest match took them over 今日 + は and
     * 急ぐ. Ranked at 296 050 and 81 833 against 122 and 1 800, so the corpus
     * settles it — but only if the segmenter can see the ranks.
     */
    @Query(
        """
        SELECT DISTINCT expression FROM word_frequencies
        WHERE rank > 0 AND rank <= :maxRank AND expression != ''
        """
    )
    suspend fun getCommonExpressions(maxRank: Int): List<String>

    @Query(
        """
        SELECT DISTINCT reading FROM word_frequencies
        WHERE rank > 0 AND rank <= :maxRank AND reading != ''
        """
    )
    suspend fun getCommonReadings(maxRank: Int): List<String>

    @Query("DELETE FROM word_frequencies WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    @Query("UPDATE word_frequencies SET dictionary = :newName WHERE dictionary = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    /** Distinct list names that actually carry frequency data, for the settings UI. */
    @Query("SELECT DISTINCT dictionary FROM word_frequencies ORDER BY dictionary")
    fun observeDictionaries(): Flow<List<String>>
}
