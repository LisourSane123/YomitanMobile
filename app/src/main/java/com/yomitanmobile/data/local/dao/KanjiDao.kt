package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.KanjiEntry

@Dao
interface KanjiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(kanjis: List<KanjiEntry>)

    @Query("SELECT * FROM kanji_entries WHERE kanji IN (:kanjis)")
    suspend fun getKanjis(kanjis: List<String>): List<KanjiEntry>

    @Query("DELETE FROM kanji_entries WHERE dictionary_name = :dictionaryName")
    suspend fun deleteByDictionary(dictionaryName: String)

    @Query("UPDATE kanji_entries SET dictionary_name = :newName WHERE dictionary_name = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM kanji_entries WHERE dictionary_name = :dictionaryName")
    suspend fun countByDictionary(dictionaryName: String): Int

    /**
     * One character. Several dictionaries can describe the same kanji, so the
     * one with the most metadata answers rather than whichever was inserted
     * first — a bare readings-only bank must not hide a full KANJIDIC row.
     */
    @Query(
        """
        SELECT * FROM kanji_entries WHERE kanji = :kanji
        ORDER BY (CASE WHEN strokes > 0 THEN 1 ELSE 0 END
                + CASE WHEN grade > 0 THEN 1 ELSE 0 END
                + CASE WHEN jlpt > 0 THEN 1 ELSE 0 END) DESC
        LIMIT 1
        """
    )
    suspend fun getKanji(kanji: String): KanjiEntry?

    /**
     * Every kanji the installed dictionaries describe, commonest first.
     *
     * Ordered by newspaper rank with the unranked last, which is the order the
     * kanji screen lists a grade in and the order a generated kanji deck is
     * introduced in — AnkiDroid hands out new cards in insertion order.
     */
    @Query(
        """
        SELECT * FROM kanji_entries
        WHERE (:grade = 0 OR grade = :grade)
          AND (:jlpt = 0 OR jlpt = :jlpt)
        GROUP BY kanji
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC, kanji ASC
        """
    )
    suspend fun listKanji(grade: Int = 0, jlpt: Int = 0): List<KanjiEntry>

    /** How many distinct kanji carry each grade, for the overview. */
    @Query(
        "SELECT grade AS bucket, COUNT(DISTINCT kanji) AS total FROM kanji_entries " +
            "WHERE grade > 0 GROUP BY grade ORDER BY grade"
    )
    suspend fun countsByGrade(): List<KanjiBucket>

    @Query(
        "SELECT jlpt AS bucket, COUNT(DISTINCT kanji) AS total FROM kanji_entries " +
            "WHERE jlpt > 0 GROUP BY jlpt ORDER BY jlpt DESC"
    )
    suspend fun countsByJlpt(): List<KanjiBucket>

    @Query("SELECT COUNT(DISTINCT kanji) FROM kanji_entries")
    suspend fun distinctCount(): Int
}

/** One row of a "how many kanji are in this bucket" rollup. */
data class KanjiBucket(val bucket: Int, val total: Int)
