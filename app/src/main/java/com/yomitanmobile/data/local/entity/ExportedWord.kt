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
    val exportCategory: String = WordCategoryClassifier.CATEGORY_OTHER,

    /**
     * Comma-separated list of all categories whose score cleared the
     * classifier's threshold for this word (see [WordCategoryClassifier.classifyAll]).
     * The legacy `exportCategory` is the first entry. Empty string means
     * an upgraded row that hasn't been reclassified yet — the stats
     * rollup falls back to [exportCategory] in that case.
     */
    @ColumnInfo(name = "export_categories")
    val exportCategories: String = "",

    /**
     * User override set via the long-press menu in DetailScreen. Empty
     * means "no override; respect classifier". When set, every stats /
     * filter view shows this value and the reclassify pass leaves it
     * alone.
     */
    @ColumnInfo(name = "manual_category")
    val manualCategory: String = ""
)

private fun localHourFromTimestamp(timestamp: Long): Int {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).hour
}
