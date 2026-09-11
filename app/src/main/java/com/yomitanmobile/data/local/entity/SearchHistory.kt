package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "search_history",
    // Same reason as favorite_words: the query text alone is not an identity
    // once two languages share an alphabet.
    indices = [Index(value = ["query", "language"], unique = true)]
)
data class SearchHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "query")
    val query: String,

    // Study language this row belongs to. Favorites and history are
    // per-language: a Japanese word in an English session's favourites is
    // one the user cannot look up, cannot export and did not ask for.
    @ColumnInfo(name = "language")
    val language: String = "ja",

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = Instant.now().toEpochMilli()
)
