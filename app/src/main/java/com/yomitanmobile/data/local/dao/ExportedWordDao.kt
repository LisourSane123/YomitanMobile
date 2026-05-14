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

/**
 * Per-row category-label tuple used by the multi-label rollup. We pull
 * the raw strings out of SQLite and aggregate in Kotlin — SQL splitting
 * of a comma-separated list with a recursive CTE was tried, but the
 * Room-generated code was unreadable and FTS-tokenizer-dependent. The
 * Kotlin path is also a single linear pass that's trivial to debug.
 */
data class ExportedCategoryRow(
    @ColumnInfo(name = "export_category")
    val exportCategory: String,
    @ColumnInfo(name = "export_categories")
    val exportCategories: String,
    @ColumnInfo(name = "manual_category")
    val manualCategory: String
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
        SELECT COALESCE(NULLIF(TRIM(export_category), ''), 'OTHER') AS category, COUNT(*) AS count
        FROM exported_words
        WHERE export_date >= :fromTimestamp
        GROUP BY COALESCE(NULLIF(TRIM(export_category), ''), 'OTHER')
        ORDER BY count DESC, category ASC
        """
    )
    suspend fun getCategoryActivitySince(fromTimestamp: Long): List<CategoryActivityCount>

    @Query(
        """
        SELECT COALESCE(NULLIF(TRIM(export_category), ''), 'OTHER') AS category, COUNT(*) AS count
        FROM exported_words
        GROUP BY COALESCE(NULLIF(TRIM(export_category), ''), 'OTHER')
        ORDER BY count DESC, category ASC
        """
    )
    fun getCategoryActivityAll(): Flow<List<CategoryActivityCount>>

    /**
     * Raw per-row labels for the multi-label rollup. Callers expand
     * `manualCategory` (if set) OR `exportCategories` (CSV) OR
     * `exportCategory` (legacy single value) in Kotlin and tally hits.
     * See [com.yomitanmobile.util.WordCategoryClassifier.classifyAll]
     * for how `exportCategories` is populated.
     */
    @Query(
        """
        SELECT export_category, export_categories, manual_category
        FROM exported_words
        WHERE export_date >= :fromTimestamp
        """
    )
    suspend fun getCategoryRowsSince(fromTimestamp: Long): List<ExportedCategoryRow>

    @Query(
        """
        SELECT export_category, export_categories, manual_category
        FROM exported_words
        """
    )
    fun getCategoryRowsAll(): Flow<List<ExportedCategoryRow>>

    /**
     * Get earliest export date (for chart range).
     */
    @Query("SELECT MIN(export_date) FROM exported_words")
    suspend fun getEarliestExportDate(): Long?

    /** Snapshot of every row — used by the reclassify pass. */
    @Query("SELECT * FROM exported_words")
    suspend fun getAllExports(): List<ExportedWord>

    /**
     * Updates only the classifier-owned columns. The manual override
     * (`manual_category`) and unrelated columns are preserved — this is
     * what makes the reclassify pass safe to run repeatedly.
     */
    @Query(
        """
        UPDATE exported_words
        SET export_category = :exportCategory,
            export_categories = :exportCategories
        WHERE id = :id
        """
    )
    suspend fun updateExportCategories(
        id: Long,
        exportCategory: String,
        exportCategories: String
    )

    /** Sets the user override; cleared by passing an empty string. */
    @Query("UPDATE exported_words SET manual_category = :manualCategory WHERE id = :id")
    suspend fun updateManualCategory(id: Long, manualCategory: String)

    /**
     * Sets the user override on every exported row matching the
     * (expression, reading) pair. A single word may be exported to
     * several decks; the user-facing chip should affect them as a
     * unit. Returns the number of rows updated so the UI can show
     * "no exports to update yet" when this word hasn't been mined.
     */
    @Query(
        """
        UPDATE exported_words
        SET manual_category = :manualCategory
        WHERE expression = :expression AND reading = :reading
        """
    )
    suspend fun updateManualCategoryForWord(
        expression: String,
        reading: String,
        manualCategory: String
    ): Int

    /**
     * Reads back the manual override (if any) for a word. The first
     * non-blank value wins — if the user set the same word on multiple
     * decks the values should be consistent because
     * [updateManualCategoryForWord] updates all rows in one statement.
     */
    @Query(
        """
        SELECT manual_category FROM exported_words
        WHERE expression = :expression AND reading = :reading
          AND manual_category != ''
        LIMIT 1
        """
    )
    suspend fun getManualCategoryForWord(expression: String, reading: String): String?

    /**
     * Get all export dates for chart computation.
     */
    @Query("SELECT export_date FROM exported_words ORDER BY export_date ASC")
    suspend fun getAllExportDates(): List<Long>
}
