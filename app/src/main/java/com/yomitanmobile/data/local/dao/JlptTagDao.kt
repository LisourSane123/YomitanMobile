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

    /** How many words the stored tags cover per level — used by the deck UI. */
    @Query("SELECT COUNT(DISTINCT expression) FROM jlpt_tags WHERE level = :level")
    suspend fun countForLevel(level: Int): Int
}
