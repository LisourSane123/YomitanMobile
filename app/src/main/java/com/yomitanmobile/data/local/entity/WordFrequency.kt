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

    // The number the list shipped, exactly as it shipped it. Which way it runs
    // depends on the list (frequency_lists.higher_is_better): a rank list says
    // 1 for its commonest word, a count list says 5 000 000. Never rewritten.
    @ColumnInfo(name = "rank")
    val rank: Int,

    // The label to render. Usually the rank as a string, but rank-based lists
    // can ship a custom displayValue (e.g. a bucketed "Top 10k").
    @ColumnInfo(name = "display_value")
    val displayValue: String,

    // Where the word stands in this list, 1 = commonest; 0 = not computed or
    // unranked. Derived from [rank] and the list's direction by
    // FrequencyPositions — only for ordering, "Top 3K" and rarity cuts, never
    // shown as the list's number.
    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int = 0
)
