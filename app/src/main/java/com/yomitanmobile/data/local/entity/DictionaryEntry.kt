package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary_entries",
    indices = [
        Index(value = ["expression"]),
        Index(value = ["reading"]),
        Index(value = ["dictionary_name"]),
        Index(value = ["frequency"]),
        // The JLPT deck generator selects a whole level in one query.
        Index(value = ["jlpt_level"])
    ]
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "expression")
    val expression: String,

    @ColumnInfo(name = "reading")
    val reading: String,

    @ColumnInfo(name = "definition")
    val definition: String,

    @ColumnInfo(name = "frequency")
    val frequency: Int = 0,

    @ColumnInfo(name = "pitch_accent")
    val pitchAccent: String = "",

    @ColumnInfo(name = "parts_of_speech")
    val partsOfSpeech: String = "",

    @ColumnInfo(name = "dictionary_name")
    val dictionaryName: String = "",

    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Int = 0,

    @ColumnInfo(name = "example_sentence")
    val exampleSentence: String = "",

    @ColumnInfo(name = "example_sentence_translation")
    val exampleSentenceTranslation: String = "",

    @ColumnInfo(name = "audio_file")
    val audioFile: String = "",

    // 0 = no JLPT tag; 1-5 = N1-N5 (jlpt-1..jlpt-5 from JMDict termTags)
    @ColumnInfo(name = "jlpt_level")
    val jlptLevel: Int = 0,

    // Study language this row belongs to ("ja" / "en"). Search filters on
    // it so a bilingual install never mixes 猫 into an English result list.
    // Taken from the dictionary's index.json sourceLanguage when it declares
    // one, otherwise from the language active at import time.
    @ColumnInfo(name = "language")
    val language: String = "ja",

    // JSON-serialized List<ExamplePair> — extracted from Jitendex structured-content
    // example containers. Empty for dictionaries without embedded examples (plain JMDict).
    @ColumnInfo(name = "examples_json")
    val examplesJson: String = ""
)
