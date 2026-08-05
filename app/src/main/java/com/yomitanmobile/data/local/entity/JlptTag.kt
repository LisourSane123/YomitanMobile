package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A JLPT level a meta dictionary assigned to one word.
 *
 * Why this exists as its own table instead of only living in
 * [DictionaryEntry.jlptLevel]: the level column is written by *updating*
 * already-imported term rows, which made the data order-dependent and
 * destructible —
 *  • importing "JLPT Vocab Tags" BEFORE the term dictionary updated nothing
 *    and the levels were gone for good,
 *  • re-importing a term dictionary deletes and re-inserts its rows, wiping
 *    every level with them (a plain JMdict carries no JLPT tags of its own).
 * Either case left the JLPT deck generator with zero candidates.
 *
 * The tag list is now stored independently of the term rows and re-applied
 * (see `DictionaryDao.applyJlptLevelsFromTags`) after every import, so the
 * install order no longer matters.
 *
 * Keyed by (expression, reading, dictionary):
 *  • `reading` is "" when the source shipped no reading — the apply step then
 *    matches on the expression alone.
 *  • `dictionary` is the source's index.json title; rows are written under the
 *    temp name and renamed at the end of the import, mirroring [WordFrequency].
 */
@Entity(
    tableName = "jlpt_tags",
    primaryKeys = ["expression", "reading", "dictionary"],
    indices = [
        Index(value = ["expression"]),
        Index(value = ["expression", "reading"]),
        Index(value = ["dictionary"])
    ]
)
data class JlptTag(
    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "dictionary")
    val dictionary: String,

    /** 1-5 = N1-N5. Higher number = easier level. */
    @ColumnInfo(name = "level")
    val level: Int
)
