package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.ExportedWord
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportedWordDao {

    @Query("SELECT * FROM exported_words WHERE expression = :expression AND reading = :reading AND deck_name = :deckName LIMIT 1")
    suspend fun findExported(expression: String, reading: String, deckName: String): ExportedWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exportedWord: ExportedWord): Long

    @Query("SELECT COUNT(*) FROM exported_words")
    suspend fun getExportedCount(): Int

    @Query("SELECT * FROM exported_words ORDER BY export_date DESC LIMIT :limit")
    fun getRecentExports(limit: Int = 50): Flow<List<ExportedWord>>

    @Query("DELETE FROM exported_words")
    suspend fun deleteAll()
}
