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
/** One spelling or reading with the best rank any installed list gives it. */
data class SurfaceRank(val surface: String, val rank: Int)

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
        SELECT expression AS surface, MIN(rank) AS rank FROM word_frequencies
        WHERE rank > 0 AND rank <= :maxRank AND expression != ''
        GROUP BY expression
        UNION ALL
        SELECT reading AS surface, MIN(rank) AS rank FROM word_frequencies
        WHERE rank > 0 AND rank <= :maxRank AND reading != ''
        GROUP BY reading
        """
    )
    suspend fun getCommonSurfaceRanks(maxRank: Int): List<SurfaceRank>

    @Query("DELETE FROM word_frequencies WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    @Query("UPDATE word_frequencies SET dictionary = :newName WHERE dictionary = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    /** Distinct list names that actually carry frequency data, for the settings UI. */
    @Query("SELECT DISTINCT dictionary FROM word_frequencies ORDER BY dictionary")
    fun observeDictionaries(): Flow<List<String>>
}
