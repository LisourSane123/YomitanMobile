package com.yomitanmobile.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Fills `word_frequencies.position` for one list: where the word stands in
 * that list, 1 = its commonest word.
 *
 * The list's own number (`rank`, `display_value`) is never touched — it is
 * what the frequency screen, the detail chips and the card show. `position`
 * exists only because "Top 3K", the search order and the deck generators'
 * rarity cut all need "how common is it" on one scale, and a list of
 * occurrence counts runs the other way:
 *
 *  • ascending list (ranks, lower = commoner): `position` is the number itself,
 *  • descending list (counts, higher = commoner): `position` is the place of
 *    the number among the list's distinct values, largest first.
 *
 * Shared by the migration and the repository so both compute it the same way.
 * The temp table stands in for window functions, which SQLite on API 26 lacks.
 */
object FrequencyPositions {

    fun recompute(db: SupportSQLiteDatabase, dictionary: String, higherIsBetter: Boolean) {
        if (!higherIsBetter) {
            db.execSQL(
                "UPDATE word_frequencies SET position = CASE WHEN rank > 0 THEN rank ELSE 0 END " +
                    "WHERE dictionary = ?",
                arrayOf(dictionary)
            )
            return
        }
        db.execSQL("DROP TABLE IF EXISTS temp.freq_position_map")
        // rowid numbers the rows in the order the SELECT hands them over.
        db.execSQL(
            "CREATE TEMP TABLE freq_position_map (position INTEGER PRIMARY KEY, value INTEGER UNIQUE)"
        )
        db.execSQL(
            "INSERT INTO temp.freq_position_map (value) " +
                "SELECT DISTINCT rank FROM word_frequencies " +
                "WHERE dictionary = ? AND rank > 0 ORDER BY rank DESC",
            arrayOf(dictionary)
        )
        db.execSQL(
            "UPDATE word_frequencies SET position = COALESCE((" +
                "SELECT position FROM temp.freq_position_map " +
                "WHERE value = word_frequencies.rank), 0) " +
                "WHERE dictionary = ?",
            arrayOf(dictionary)
        )
        db.execSQL("DROP TABLE IF EXISTS temp.freq_position_map")
    }
}
