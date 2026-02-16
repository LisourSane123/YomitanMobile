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
        Index(value = ["frequency"])
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
    val audioFile: String = ""
)
