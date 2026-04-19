package com.yomitanmobile.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.ExportedWord
import kotlinx.coroutines.flow.Flow

data class HourlyActivityCount(
    @ColumnInfo(name = "hour")
    val hour: Int,
    @ColumnInfo(name = "count")
    val count: Int
)

data class CategoryActivityCount(
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "count")
    val count: Int
)

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

    @Query("SELECT COUNT(*) FROM exported_words WHERE export_date >= :startOfDay")
    suspend fun getExportedCountSince(startOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM exported_words WHERE expression = :expression AND reading = :reading")
    suspend fun countExportsForWord(expression: String, reading: String): Int

    @Query("SELECT * FROM exported_words WHERE export_date >= :fromTimestamp ORDER BY export_date DESC")
    suspend fun getExportsSince(fromTimestamp: Long): List<ExportedWord>

    @Query(
        """
        SELECT export_hour AS hour, COUNT(*) AS count
        FROM exported_words
        WHERE export_date >= :fromTimestamp AND export_hour BETWEEN 0 AND 23
        GROUP BY export_hour
        ORDER BY count DESC, hour ASC
        """
    )
    suspend fun getHourlyActivitySince(fromTimestamp: Long): List<HourlyActivityCount>

    @Query(
        """
        SELECT export_category AS category, COUNT(*) AS count
        FROM exported_words
        WHERE export_date >= :fromTimestamp
        GROUP BY export_category
        ORDER BY count DESC, category ASC
        """
    )
    suspend fun getCategoryActivitySince(fromTimestamp: Long): List<CategoryActivityCount>

    @Query(
        """
        SELECT export_category AS category, COUNT(*) AS count
        FROM exported_words
        GROUP BY export_category
        ORDER BY count DESC, category ASC
        """
    )
    fun getCategoryActivityAll(): Flow<List<CategoryActivityCount>>

    /**
     * Get earliest export date (for chart range).
     */
    @Query("SELECT MIN(export_date) FROM exported_words")
    suspend fun getEarliestExportDate(): Long?

    /**
     * Get all export dates for chart computation.
     */
    @Query("SELECT export_date FROM exported_words ORDER BY export_date ASC")
    suspend fun getAllExportDates(): List<Long>
}
