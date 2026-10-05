package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which way one installed frequency list's numbers run.
 *
 * Yomitan's meta format carries a bare number per word and says nothing about
 * direction. Most lists ship a rank (1 = the commonest word, lower is better);
 * some ship the occurrence count they were built from (Innocent Corpus and the
 * "word count" lists: 5 000 000 = the commonest, HIGHER is better).
 *
 * The direction is detected once, at import, from the shape of the numbers
 * ([com.yomitanmobile.domain.model.FrequencyDirection]) and shown on the
 * frequency screen. The numbers themselves are never converted: they stay as
 * the list shipped them, and only `word_frequencies.position` is derived.
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
