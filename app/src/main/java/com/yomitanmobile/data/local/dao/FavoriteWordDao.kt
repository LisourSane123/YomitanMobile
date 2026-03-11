package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.FavoriteWord
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteWordDao {

    @Query("SELECT * FROM favorite_words ORDER BY added_date DESC")
    fun getAllFavorites(): Flow<List<FavoriteWord>>

    @Query("SELECT * FROM favorite_words ORDER BY added_date DESC LIMIT :limit")
    fun getRecentFavorites(limit: Int = 50): Flow<List<FavoriteWord>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_words WHERE expression = :expression AND reading = :reading)")
    fun isFavorite(expression: String, reading: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_words WHERE expression = :expression AND reading = :reading)")
    suspend fun isFavoriteSync(expression: String, reading: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favoriteWord: FavoriteWord): Long

    @Query("DELETE FROM favorite_words WHERE expression = :expression AND reading = :reading")
    suspend fun delete(expression: String, reading: String)

    @Query("DELETE FROM favorite_words WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM favorite_words")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM favorite_words")
    suspend fun getCount(): Int

    @Query("SELECT * FROM favorite_words ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavorite(): FavoriteWord?
}
