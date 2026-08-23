package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.FavoriteWord
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteWordDao {

    @Query("SELECT * FROM favorite_words WHERE language = :language ORDER BY added_date DESC")
    fun getAllFavorites(language: String): Flow<List<FavoriteWord>>

    @Query(
        "SELECT * FROM favorite_words WHERE language = :language " +
            "ORDER BY added_date DESC LIMIT :limit"
    )
    fun getRecentFavorites(language: String, limit: Int = 50): Flow<List<FavoriteWord>>

    // Language-scoped like the listing: "no" is a word in both English and
    // Spanish, and without this the star could show as set for a favourite
    // that the current language's list does not contain.
    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorite_words " +
            "WHERE expression = :expression AND reading = :reading AND language = :language)"
    )
    fun isFavorite(expression: String, reading: String, language: String): Flow<Boolean>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorite_words " +
            "WHERE expression = :expression AND reading = :reading AND language = :language)"
    )
    suspend fun isFavoriteSync(expression: String, reading: String, language: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favoriteWord: FavoriteWord): Long

    @Query(
        "DELETE FROM favorite_words " +
            "WHERE expression = :expression AND reading = :reading AND language = :language"
    )
    suspend fun delete(expression: String, reading: String, language: String)

    @Query("DELETE FROM favorite_words WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM favorite_words")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM favorite_words WHERE language = :language")
    suspend fun getCount(language: String): Int

    // The widget shows this one; scoped so it can't surface a word from a
    // language the user is not studying.
    @Query("SELECT * FROM favorite_words WHERE language = :language ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFavorite(language: String): FavoriteWord?
}
