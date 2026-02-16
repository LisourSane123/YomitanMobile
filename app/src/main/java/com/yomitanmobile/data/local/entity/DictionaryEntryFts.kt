package com.yomitanmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = DictionaryEntry::class)
@Entity(tableName = "dictionary_entries_fts")
data class DictionaryEntryFts(
    val expression: String,
    val reading: String,
    val definition: String
)
