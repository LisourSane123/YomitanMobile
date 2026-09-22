package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.JlptTag

/**
 * Access to the persisted [JlptTag] list. See the entity for why JLPT levels
 * are kept outside `dictionary_entries`.
 */
@Dao
interface JlptTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<JlptTag>)

    @Query("DELETE FROM jlpt_tags WHERE dictionary = :dictionary")
    suspend fun deleteByDictionary(dictionary: String)

    @Query("UPDATE jlpt_tags SET dictionary = :newName WHERE dictionary = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM jlpt_tags")
    suspend fun count(): Int

    /**
     * How many words the stored tags of ONE language cover at a level — used
     * by the deck UI. Per language because JLPT and CEFR share the numbers
     * (CEFR B2 is stored as 3, JLPT N3 too).
     */
    @Query(
        """
        SELECT COUNT(DISTINCT t.expression) FROM jlpt_tags t
        WHERE t.level = :level
          AND COALESCE((SELECT d.language FROM dictionaries d WHERE d.name = t.dictionary LIMIT 1), 'ja') = :language
        """
    )
    suspend fun countForLevel(level: Int, language: String): Int
}
