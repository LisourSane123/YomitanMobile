package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yomitanmobile.data.local.entity.DictionaryInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryInfoDao {

    @Query("SELECT * FROM dictionaries ORDER BY priority DESC")
    fun getAllDictionaries(): Flow<List<DictionaryInfo>>

    @Query("SELECT * FROM dictionaries WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): DictionaryInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(info: DictionaryInfo): Long

    @Update
    suspend fun update(info: DictionaryInfo)

    @Query("DELETE FROM dictionaries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dictionaries WHERE name = :name")
    suspend fun deleteByName(name: String)
}
