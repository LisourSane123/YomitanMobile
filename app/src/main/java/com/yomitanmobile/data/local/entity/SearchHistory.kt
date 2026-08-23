package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "search_history",
    indices = [Index(value = ["query"], unique = true)]
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
