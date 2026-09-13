package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What one installed frequency list's numbers MEAN.
 *
 * Yomitan's meta format carries a bare number per word and says nothing about
 * which direction it runs. Most lists ship a rank (1 = the commonest word,
 * lower is better), but some ship the raw occurrence count they were built
 * from (Innocent Corpus and the hand-made "word count" lists: 5 000 000 = the
 * commonest word, HIGHER is better). Both land in the same column, and the
 * whole app — search order, the number stamped on a card, the "too rare" cut
 * in both deck generators — reads that column as "lower is better".
 *
 * So the direction is resolved once, at import, and the count-based lists are
 * converted into ranks in place ([higherIsBetter] = true means "this list was
 * counted, and `rank` now holds the position we derived from it"). The
 * original number survives in `display_value`, which is both what the detail
 * screen shows and what a re-conversion is computed from — so flipping this
 * flag by hand is lossless in either direction.
 */
@Entity(tableName = "frequency_lists")
data class FrequencyListSetting(
    @PrimaryKey
    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** True when the source numbers were occurrence counts, not ranks. */
    @ColumnInfo(name = "higher_is_better")
    val higherIsBetter: Boolean = false,

    /** False once the user has overridden what the detector decided. */
    @ColumnInfo(name = "auto_detected")
    val autoDetected: Boolean = true
)
