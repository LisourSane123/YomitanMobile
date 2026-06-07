package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Per-source frequency rank for a word.
 *
 * Unlike the single [DictionaryEntry.frequency] column (which holds the best
 * rank across all installed lists, used only for search ordering), this table
 * keeps every list's rank separately so the UI can show them side by side
 * (e.g. "JPDB #1203 · BCCWJ #980") in a user-chosen priority order.
 *
 * Keyed by (expression, reading, dictionary):
 *  • `reading` is "" when the source list carried no reading — lookups match
 *    both the exact reading and the empty-reading fallback.
 *  • `dictionary` is the source list's index.json title (e.g. "JPDBv2",
 *    "BCCWJ"). During import rows are written under the temp name and renamed
 *    to the real title at the end, mirroring [DictionaryEntry] import.
 */
@Entity(
    tableName = "word_frequencies",
    primaryKeys = ["expression", "reading", "dictionary"],
    indices = [
        Index(value = ["expression"]),
        Index(value = ["expression", "reading"]),
        Index(value = ["dictionary"])
    ]
)
data class WordFrequency(
    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    // Numeric rank used for ordering / "best frequency" rollup. Lower = more
    // frequent.
    @ColumnInfo(name = "rank")
    val rank: Int,

    // The label to render. Usually the rank as a string, but rank-based lists
    // can ship a custom displayValue (e.g. a bucketed "Top 10k").
    @ColumnInfo(name = "display_value")
    val displayValue: String
)
