package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.DictionaryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    @Query("""
        SELECT dictionary_entries.* FROM dictionary_entries
        JOIN dictionary_entries_fts ON dictionary_entries.rowid = dictionary_entries_fts.rowid
        WHERE dictionary_entries_fts MATCH :query
        ORDER BY CASE WHEN dictionary_entries.frequency > 0 THEN 0 ELSE 1 END,
                 dictionary_entries.frequency ASC,
                 LENGTH(dictionary_entries.expression) ASC
        LIMIT :limit
    """)
    fun searchFts(query: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE expression = :expression ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC")
    fun findByExpression(expression: String): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE reading = :reading ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC")
    fun findByReading(reading: String): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE reading = :reading ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC")
    suspend fun getByReading(reading: String): List<DictionaryEntry>

    @Query("""
        SELECT * FROM dictionary_entries 
        WHERE expression LIKE :prefix || '%' OR reading LIKE :prefix || '%'
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
                 frequency ASC, LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchPrefix(prefix: String, limit: Int = 30): Flow<List<DictionaryEntry>>

    @Query("""
        SELECT * FROM dictionary_entries
        WHERE expression = :query 
           OR reading = :query
           OR expression LIKE :query || '%'
           OR reading LIKE :query || '%'
        ORDER BY 
            CASE 
                WHEN expression = :query THEN 0
                WHEN reading = :query THEN 1
                ELSE 2
            END,
            CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
            frequency ASC,
            LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchCombined(query: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE id = :id")
    suspend fun getById(id: Long): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryEntry): Long

    @Query("DELETE FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun deleteByDictionary(dictionaryName: String)

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun getEntryCount(): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun getEntryCountForDictionary(dictionaryName: String): Int

    @Query("INSERT INTO dictionary_entries_fts(dictionary_entries_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()

    @Query("UPDATE dictionary_entries SET dictionary_name = :newName WHERE dictionary_name = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND frequency = 0")
    suspend fun updateFrequency(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression")
    suspend fun updateFrequencyForce(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET pitch_accent = :pitchAccent WHERE expression = :expression AND (pitch_accent = '' OR pitch_accent IS NULL)")
    suspend fun updatePitchAccent(expression: String, pitchAccent: String)

    @Query("UPDATE dictionary_entries SET pitch_accent = :pitchAccent WHERE expression = :expression")
    suspend fun updatePitchAccentForce(expression: String, pitchAccent: String)

    @androidx.room.Transaction
    suspend fun updateFrequencyBatch(batch: Map<String, Int>) {
        for ((expression, frequency) in batch) {
            updateFrequencyForce(expression, frequency)
        }
    }

    @androidx.room.Transaction
    suspend fun updatePitchAccentBatch(batch: Map<String, String>) {
        for ((expression, pitchAccent) in batch) {
            updatePitchAccentForce(expression, pitchAccent)
        }
    }
}
