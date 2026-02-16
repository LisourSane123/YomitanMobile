package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exported_words",
    indices = [
        Index(value = ["expression", "reading", "deck_name"], unique = true)
    ]
)
data class ExportedWord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "deck_name")
    val deckName: String,

    @ColumnInfo(name = "anki_note_id")
    val ankiNoteId: Long = 0,

    @ColumnInfo(name = "export_date")
    val exportDate: Long = System.currentTimeMillis()
)
