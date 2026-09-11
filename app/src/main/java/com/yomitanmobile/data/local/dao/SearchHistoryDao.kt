package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.SearchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query(
        "SELECT * FROM search_history WHERE language = :language " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun getRecentSearches(language: String, limit: Int = 20): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(searchHistory: SearchHistory): Long

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Scoped for the same reason as the favourites table: the history the user
    // is looking at is one language's, and so is the one they clear.
    @Query("DELETE FROM search_history WHERE language = :language")
    suspend fun deleteAll(language: String)

    @Query("SELECT COUNT(*) FROM search_history WHERE language = :language")
    suspend fun getCount(language: String): Int
}
