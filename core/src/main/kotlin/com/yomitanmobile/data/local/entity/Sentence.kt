package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentences",
    indices = [
        Index(value = ["word_expression"]),
        Index(value = ["word_reading"])
    ]
)
data class Sentence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "word_expression")
    val wordExpression: String,

    @ColumnInfo(name = "word_reading")
    val wordReading: String = "",

    @ColumnInfo(name = "sentence_japanese")
    val sentenceJapanese: String,

    @ColumnInfo(name = "sentence_english")
    val sentenceEnglish: String,

    @ColumnInfo(name = "source")
    val source: String = "tatoeba" // "tatoeba", "user_provided", etc.
)
