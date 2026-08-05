package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One Japanese word found in the user's AnkiDroid collection.
 *
 * The collection scan (`AnkiCollectionIndex`) reads every note through the
 * AnkiDroid content provider, which takes seconds on a large collection — far
 * too slow to run each time a word detail screen opens. The result is stored
 * here instead, so "do I already have this card?" is a primary-key lookup and
 * works even while AnkiDroid is closed or its permission is temporarily gone.
 *
 * [word] is the normalized key from `AnkiNoteFieldIndexer.normalizeKey`
 * (whitespace stripped, wave dashes trimmed), which is also readable as-is —
 * it is a plain Japanese headword, never HTML or a sentence.
 *
 * [source] is the note type the word was first seen in ("Core 2k", "Kaishi
 * 1.5k", "Yomitan-Mobile-v8"…), kept purely so the scan screen can show where
 * the matches came from and the user can sanity-check the scan.
 */
@Entity(
    tableName = "anki_collection_words",
    indices = [Index(value = ["source"])]
)
data class AnkiCollectionWord(
    @PrimaryKey
    @ColumnInfo(name = "word")
    val word: String,

    @ColumnInfo(name = "source")
    val source: String = "",

    /** When the scan that produced this row ran (epoch millis). */
    @ColumnInfo(name = "scanned_at")
    val scannedAt: Long = 0L
)
