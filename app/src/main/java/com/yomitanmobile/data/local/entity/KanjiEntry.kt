package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kanji_entries",
    indices = [Index(value = ["kanji"])]
)
data class KanjiEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "kanji")
    val kanji: String,

    @ColumnInfo(name = "onyomi")
    val onyomi: String,

    @ColumnInfo(name = "kunyomi")
    val kunyomi: String,

    @ColumnInfo(name = "meanings")
    val meanings: String,

    @ColumnInfo(name = "dictionary_name")
    val dictionaryName: String
)
