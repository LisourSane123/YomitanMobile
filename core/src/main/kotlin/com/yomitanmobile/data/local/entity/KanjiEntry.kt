package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "kanji_entries",
    indices = [Index(value = ["kanji"])]
)
data class KanjiEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "kanji")
    val kanji: String,

    @ColumnInfo(name = "onyomi")
    val onyomi: String,

    @ColumnInfo(name = "kunyomi")
    val kunyomi: String,

    @ColumnInfo(name = "meanings")
    val meanings: String,

    @ColumnInfo(name = "dictionary_name")
    val dictionaryName: String,

    /**
     * The kanji's own metadata, from the two places a Yomitan kanji bank puts
     * it: the tag string at index 3 ("jouyou kyouiku grade1 jlpt5") and the
     * stats object at index 5 ("grade", "jlpt", "strokes", "freq").
     *
     * Dropped until now, which is why the app could show a kanji's readings
     * but could not answer "how much of jōyō do I know" — the question a
     * learner actually asks about kanji. Zero means the dictionary did not
     * say; a KANJIDIC installed before this existed reads as zero everywhere
     * until it is re-imported.
     */
    @ColumnInfo(name = "grade")
    val grade: Int = 0,

    /** 5 = N5 … 1 = N1, matching the JLPT column on term rows. */
    @ColumnInfo(name = "jlpt")
    val jlpt: Int = 0,

    @ColumnInfo(name = "strokes")
    val strokes: Int = 0,

    /** Newspaper frequency rank (1 = commonest), as KANJIDIC ships it. */
    @ColumnInfo(name = "frequency")
    val frequency: Int = 0
)
