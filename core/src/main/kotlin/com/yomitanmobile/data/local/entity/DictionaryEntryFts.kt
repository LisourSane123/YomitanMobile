package com.yomitanmobile.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Definition search index.
 *
 * `unicode61`, not the default `simple` tokenizer. `simple` case-folds ASCII
 * only, so a Polish or Spanish query matched only when its capitalisation
 * happened to agree with the dictionary's: searching "żaba" found nothing
 * against a definition reading "Żaba", while "gad" / "GAD" worked fine
 * because those are ASCII. unicode61 folds the whole Unicode range and, by
 * default, strips diacritics too — so "ano" also finds "año", which is what
 * someone typing without a Polish or Spanish keyboard layout needs.
 */
@Fts4(
    contentEntity = DictionaryEntry::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "dictionary_entries_fts")
data class DictionaryEntryFts(
    val expression: String,
    val reading: String,
    val definition: String
)
