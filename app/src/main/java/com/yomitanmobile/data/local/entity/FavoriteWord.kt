package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "favorite_words",
    indices = [
        Index(value = ["expression", "reading"], unique = true)
    ]
)
data class FavoriteWord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "definition_preview")
    val definitionPreview: String = "",

    @ColumnInfo(name = "entry_id")
    val entryId: Long = 0,

    // Study language this row belongs to. Favorites and history are
    // per-language: a Japanese word in an English session's favourites is
    // one the user cannot look up, cannot export and did not ask for.
    @ColumnInfo(name = "language")
    val language: String = "ja",

    @ColumnInfo(name = "added_date")
    val addedDate: Long = Instant.now().toEpochMilli()
)
