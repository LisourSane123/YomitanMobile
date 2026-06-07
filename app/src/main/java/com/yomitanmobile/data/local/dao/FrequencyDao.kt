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

    @Query("DELETE FROM word_frequencies WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    @Query("UPDATE word_frequencies SET dictionary = :newName WHERE dictionary = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    /** Distinct list names that actually carry frequency data, for the settings UI. */
    @Query("SELECT DISTINCT dictionary FROM word_frequencies ORDER BY dictionary")
    fun observeDictionaries(): Flow<List<String>>
}
