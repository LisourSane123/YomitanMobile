package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.KanjiEntry

@Dao
interface KanjiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(kanjis: List<KanjiEntry>)

    @Query("SELECT * FROM kanji_entries WHERE kanji IN (:kanjis)")
    suspend fun getKanjis(kanjis: List<String>): List<KanjiEntry>

    @Query("DELETE FROM kanji_entries WHERE dictionary_name = :dictionaryName")
    suspend fun deleteByDictionary(dictionaryName: String)

    @Query("UPDATE kanji_entries SET dictionary_name = :newName WHERE dictionary_name = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM kanji_entries WHERE dictionary_name = :dictionaryName")
    suspend fun countByDictionary(dictionaryName: String): Int
}
