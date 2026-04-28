package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.Sentence
import kotlinx.coroutines.flow.Flow

@Dao
interface SentenceDao {

    @Query("SELECT * FROM sentences WHERE word_expression = :expression LIMIT 5")
    fun getSentencesByExpression(expression: String): Flow<List<Sentence>>

    @Query("SELECT * FROM sentences WHERE word_expression = :expression LIMIT 5")
    suspend fun getSentencesByExpressionSuspend(expression: String): List<Sentence>

    @Query("SELECT * FROM sentences WHERE word_reading = :reading LIMIT 5")
    suspend fun getSentencesByReading(reading: String): List<Sentence>

    @Query("SELECT * FROM sentences WHERE word_expression = :expression OR word_reading = :reading LIMIT 5")
    suspend fun getSentencesByExpressionOrReading(expression: String, reading: String): List<Sentence>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sentence: Sentence)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sentences: List<Sentence>)

    @Query("DELETE FROM sentences WHERE word_expression = :expression")
    suspend fun deleteSentencesByExpression(expression: String)

    @Query("SELECT COUNT(*) FROM sentences")
    suspend fun getSentenceCount(): Int
}
