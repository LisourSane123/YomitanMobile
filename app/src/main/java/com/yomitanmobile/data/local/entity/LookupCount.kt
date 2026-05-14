package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Tracks how many times the user has opened the detail page for a
 * particular word. Powers the "you've looked this up N times — maybe
 * favorite it?" prompt on [com.yomitanmobile.ui.detail.DetailScreen].
 *
 * Keyed by `(expression, reading)` rather than `WordEntry.id` because
 * the same surface form can appear under several entry IDs (multi-dict
 * imports, alternate readings) and the user perceives them as one
 * "word I keep hitting."
 *
 * The two timestamp columns are kept for future analysis ("hot last
 * week, cold this week") but are not yet read by UI code — the audit
 * lessons about premature features apply.
 */
@Entity(
    tableName = "lookup_counts",
    primaryKeys = ["expression", "reading"]
)
data class LookupCount(
    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "lookup_count", defaultValue = "1")
    val lookupCount: Int = 1,

    @ColumnInfo(name = "first_lookup")
    val firstLookup: Long,

    @ColumnInfo(name = "last_lookup")
    val lastLookup: Long
)
