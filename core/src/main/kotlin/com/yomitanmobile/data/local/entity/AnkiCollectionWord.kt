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
    val scannedAt: Long = 0L,

    /**
     * Whether a card carrying this word is mature — Anki's own definition, an
     * interval of 21 days or more, and not suspended.
     *
     * "I have a card for this" and "I know this" are different claims, and the
     * app used to make the first while meaning the second. A word added
     * yesterday is not knowledge; the figures that say how much of a text the
     * reader understands, and how much of a kanji grade they cover, are only
     * honest about the mature half.
     *
     * False also covers "the provider would not answer the maturity search"
     * (older AnkiDroid), which is why nothing is ever HIDDEN on the strength
     * of this column — it only ever adds a second, stricter number next to the
     * first.
     */
    @ColumnInfo(name = "mature")
    val mature: Boolean = false,

    /**
     * Whether a card carrying this word has actually been started: not new,
     * not suspended ([AnkiCollectionIndex.STUDIED_SEARCH]).
     *
     * The middle claim between [mature] and "a card exists". A new card has
     * never been shown, so its word is in the collection and means nothing
     * yet; a suspended one was taken out of rotation on purpose. Neither
     * belongs in a list of characters to study, which is what this column is
     * for — and, like [mature], nothing is ever hidden from the duplicate
     * check on its strength: false also means "the provider would not answer
     * that search".
     */
    @ColumnInfo(name = "studied")
    val studied: Boolean = false
)
