package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.FrequencyListSetting
import com.yomitanmobile.data.local.entity.WordFrequency
import kotlinx.coroutines.flow.Flow

/**
 * Access to the per-source [WordFrequency] table. See the entity for the
 * temp→real dictionary-name rename pattern used during import.
 */
/** One spelling or reading with the best rank any installed list gives it. */
data class SurfaceRank(val surface: String, val rank: Int)

/**
 * The shape of one list's numbers, which is how the app tells a rank list from
 * a list of raw occurrence counts. See [com.yomitanmobile.domain.model.FrequencyDirection].
 */
data class FrequencyListStats(
    val rowCount: Int,
    val distinctValues: Int,
    val maxValue: Int,
    val minValue: Int
)

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
        ORDER BY position ASC
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
        SELECT expression AS surface, MIN(position) AS rank FROM word_frequencies
        WHERE position > 0 AND position <= :maxRank AND expression != ''
        GROUP BY expression
        UNION ALL
        SELECT reading AS surface, MIN(position) AS rank FROM word_frequencies
        WHERE position > 0 AND position <= :maxRank AND reading != ''
        GROUP BY reading
        """
    )
    suspend fun getCommonSurfaceRanks(maxRank: Int): List<SurfaceRank>

    /**
     * One named list's ranks for a batch of spellings — what the search list
     * shows beside each word, so the leading list is visible before the user
     * opens anything. Chunk the caller's expressions: SQLite takes 999 bound
     * variables per statement.
     */
    @Query(
        """
        SELECT * FROM word_frequencies
        WHERE dictionary = :dictionary AND expression IN (:expressions)
        """
    )
    suspend fun getForDictionary(
        dictionary: String,
        expressions: List<String>
    ): List<WordFrequency>

    /**
     * How one list's numbers are distributed, for [FrequencyListStats].
     * `rank` holds the numbers as shipped, so this is the list's real shape.
     */
    @Query(
        """
        SELECT COUNT(*) AS rowCount, COUNT(DISTINCT rank) AS distinctValues,
               MAX(rank) AS maxValue, MIN(rank) AS minValue
        FROM word_frequencies WHERE dictionary = :dictionary AND rank > 0
        """
    )
    suspend fun statsFor(dictionary: String): FrequencyListStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListSetting(setting: FrequencyListSetting)

    @Query("SELECT * FROM frequency_lists WHERE dictionary = :dictionary")
    suspend fun getListSetting(dictionary: String): FrequencyListSetting?

    @Query("SELECT * FROM frequency_lists")
    suspend fun getListSettings(): List<FrequencyListSetting>

    /** What each installed list's numbers mean, for the settings screen. */
    @Query("SELECT * FROM frequency_lists")
    fun observeListSettings(): Flow<List<FrequencyListSetting>>

    @Query("DELETE FROM frequency_lists WHERE dictionary = :dictionary")
    suspend fun deleteListSetting(dictionary: String)

    @Query("DELETE FROM word_frequencies WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    @Query("UPDATE word_frequencies SET dictionary = :newName WHERE dictionary = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    /** Distinct list names that actually carry frequency data, for the settings UI. */
    @Query("SELECT DISTINCT dictionary FROM word_frequencies ORDER BY dictionary")
    fun observeDictionaries(): Flow<List<String>>
}
