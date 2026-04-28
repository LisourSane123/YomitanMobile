package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yomitanmobile.util.WordCategoryClassifier
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
    val exportDate: Long = Instant.now().toEpochMilli(),

    @ColumnInfo(name = "export_hour")
    val exportHour: Int = localHourFromTimestamp(exportDate),

    @ColumnInfo(name = "export_category")
    val exportCategory: String = WordCategoryClassifier.CATEGORY_OTHER
)

private fun localHourFromTimestamp(timestamp: Long): Int {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).hour
}
