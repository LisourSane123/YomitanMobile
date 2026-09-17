package com.yomitanmobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One pronunciation file from the user's own audio archive, indexed by the
 * word it pronounces.
 *
 * The app's only voice used to be the device's TTS engine, which reads a
 * headword with no context: it regularly picks the wrong reading for a kanji
 * spelling (行った, 一日, 開く) and carries no pitch accent at all — while the
 * card printed above it shows the accent pattern from Kanjium. The card said
 * one thing and sounded like another.
 *
 * Archives are not redistributable (the same reason the 国語辞典 are not), so
 * the user brings their own folder and this table is the index over it. Only
 * the index is stored; the files stay where they are and are read back through
 * [uri], which is why the folder is taken with a persisted SAF tree
 * permission.
 *
 * [key] is a lookup key, not a file name: one file is indexed several times
 * over — under its expression, under its reading, and under the pair — because
 * archives disagree about which of the two they name a file after. [priority]
 * ranks those keys so an exact expression+reading hit beats a bare reading
 * that several homophones share.
 */
@Entity(
    tableName = "audio_files",
    indices = [Index(value = ["key"])]
)
data class AudioFile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Normalized lookup key: expression, reading, or the two joined by a tab. */
    @ColumnInfo(name = "key")
    val key: String,

    /** Document URI of the file inside the user's tree. */
    @ColumnInfo(name = "uri")
    val uri: String,

    /** Shown on the settings screen so a bad archive is recognisable. */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** Lower is better: 0 = expression + reading, 1 = expression, 2 = reading. */
    @ColumnInfo(name = "priority")
    val priority: Int
)
