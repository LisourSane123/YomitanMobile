package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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

    @ColumnInfo(name = "added_date")
    val addedDate: Long = System.currentTimeMillis()
)
