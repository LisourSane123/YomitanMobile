package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.DictionaryEntry
import kotlinx.coroutines.flow.Flow

data class FrequencyUpdate(
    val expression: String,
    val reading: String?,
    val frequency: Int,
    // The label the source list ships (rank as a string, or a bucketed
    // label). Blank falls back to [frequency] at the storage layer.
    val displayValue: String = ""
)

data class JlptUpdate(
    val expression: String,
    val reading: String?,
    val level: Int
)

/** Lightweight (expression, reading) projection for furigana generation. */
data class ExpressionReading(
    val expression: String,
    val reading: String,
    val frequency: Int
)

@Dao
interface DictionaryDao {

    /**
     * Exact-match lookup on expression OR reading (no prefix wildcards).
     * Used for deconjugation alternatives: a base form like 見る should
     * surface only itself, not every longer entry that starts with 見る
     * (which the prefix-LIKE [searchCombined] would drag in).
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE language = :language
          AND (expression = :exactQuery OR reading = :exactQuery)
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
                 frequency ASC,
                 LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchExact(exactQuery: String, language: String, limit: Int = 50): Flow<List<DictionaryEntry>>

    @Query(
        "SELECT * FROM dictionary_entries WHERE reading = :reading AND language = :language " +
            "ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC"
    )
    suspend fun getByReading(reading: String, language: String): List<DictionaryEntry>

    /**
     * Reading lookup for a batch of exact expressions, used to synthesise
     * furigana for example sentences that shipped without ruby. Only kanji
     * words have distinct readings worth annotating, so callers pass
     * kanji-containing candidates. Ordering surfaces the highest-priority
     * reading first (frequency-ranked, then shortest expression) so the
     * caller can keep the first row per expression.
     */
    @Query("""
        SELECT expression, reading, frequency FROM dictionary_entries
        WHERE expression IN (:expressions) AND reading != '' AND language = :language
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC
    """)
    suspend fun getReadingsForExpressions(
        expressions: List<String>,
        language: String
    ): List<ExpressionReading>

    /**
     * Prefix + exact search over expression and reading.
     *
     * The prefix match is expressed as a RANGE over the indexed column
     * (`expression >= :prefixStart AND expression < :prefixEnd`) rather than
     * `LIKE :q || '%'`. That is not a style choice: SQLite applies its LIKE
     * optimisation only to a literal prefix pattern, and a concatenation —
     * with an `ESCAPE` clause on top — disqualifies it. The old form planned
     * as SCAN TABLE and read every row of the dictionary on every keystroke
     * (measured: 20 ms over 240k rows in memory, worse off disk). The range
     * form seeks into the expression / reading indexes and costs about 1 ms on
     * the same data. Wildcards need no escaping any more either: `%` and `_`
     * are ordinary characters to a range comparison.
     *
     * Three ranges per column — the query as typed, lower-cased, and with its
     * first letter capitalised. Range comparison uses BINARY collation and is
     * therefore case-sensitive where `LIKE` was not, while a phone keyboard
     * capitalises the first letter on its own and dictionaries hold both
     * `dog` and `Dogma`. Those three forms cover what a user actually types;
     * a shouted `DOG` is the accepted gap. For a Japanese query all three are
     * the same string and the UNION folds them into one lookup.
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE id IN (
                SELECT id FROM dictionary_entries
                WHERE expression >= :prefixStart AND expression < :prefixEnd
            UNION
                SELECT id FROM dictionary_entries
                WHERE reading >= :prefixStart AND reading < :prefixEnd
            UNION
                SELECT id FROM dictionary_entries
                WHERE expression >= :lowerPrefixStart AND expression < :lowerPrefixEnd
            UNION
                SELECT id FROM dictionary_entries
                WHERE reading >= :lowerPrefixStart AND reading < :lowerPrefixEnd
            UNION
                SELECT id FROM dictionary_entries
                WHERE expression >= :titlePrefixStart AND expression < :titlePrefixEnd
            UNION
                SELECT id FROM dictionary_entries
                WHERE reading >= :titlePrefixStart AND reading < :titlePrefixEnd
          )
          AND language = :language
        ORDER BY
            CASE
                WHEN expression = :exactQuery THEN 0
                WHEN reading = :exactQuery THEN 1
                ELSE 2
            END,
            CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
            frequency ASC,
            LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchCombined(
        exactQuery: String,
        prefixStart: String,
        prefixEnd: String,
        lowerPrefixStart: String,
        lowerPrefixEnd: String,
        titlePrefixStart: String,
        titlePrefixEnd: String,
        language: String,
        limit: Int = 50
    ): Flow<List<DictionaryEntry>>

    /**
     * Substring match: every entry that CONTAINS the query somewhere other
     * than at the start (食欲 for 欲), which the prefix-only [searchCombined]
     * can never reach. Kanji carry meaning inside compounds, so looking up a
     * single character and seeing only the words that begin with it hides
     * most of what the character is used for.
     *
     * Rows that already prefix-match are excluded here rather than deduped by
     * the caller: they are the frequent ones, so without the exclusion the
     * LIMIT would be spent entirely on results [searchCombined] already
     * returned, and the compounds — the whole point of this query — would
     * fall off the end.
     *
     * `LIKE '%x%'` cannot use an index for seeking, but the id-subquery form
     * keeps the scan inside the small `expression` / `reading` indexes (which
     * carry the rowid) instead of dragging every `definition` blob through
     * the page cache. Callers gate this on query shape — see
     * `SearchDictionaryUseCase.shouldSearchSubstring`.
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE id IN (
                SELECT id FROM dictionary_entries
                WHERE expression LIKE '%' || :likeQuery || '%' ESCAPE '\'
            UNION
                SELECT id FROM dictionary_entries
                WHERE reading LIKE '%' || :likeQuery || '%' ESCAPE '\'
          )
          AND language = :language
          AND expression NOT LIKE :likeQuery || '%' ESCAPE '\'
          AND reading NOT LIKE :likeQuery || '%' ESCAPE '\'
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END,
                 frequency ASC,
                 LENGTH(expression) ASC
        LIMIT :limit
    """)
    fun searchContains(
        likeQuery: String,
        language: String,
        limit: Int = 30
    ): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE id = :id")
    suspend fun getById(id: Long): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<DictionaryEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DictionaryEntry): Long

    @Query("DELETE FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun deleteByDictionary(dictionaryName: String)

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    suspend fun getEntryCount(): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE language = :language")
    suspend fun getEntryCountForLanguage(language: String): Int

    @Query("SELECT COUNT(*) FROM dictionary_entries WHERE dictionary_name = :dictionaryName")
    suspend fun getEntryCountForDictionary(dictionaryName: String): Int

    @Query("INSERT INTO dictionary_entries_fts(dictionary_entries_fts) VALUES('rebuild')")
    suspend fun rebuildFtsIndex()

    /**
     * Search by English definition text using FTS.
     * Matches words whose definition column contains the query string.
     */
    @Query("""
        SELECT dictionary_entries.* FROM dictionary_entries
        JOIN dictionary_entries_fts ON dictionary_entries.rowid = dictionary_entries_fts.rowid
        WHERE dictionary_entries_fts MATCH :query
          AND dictionary_entries.language = :language
        ORDER BY CASE WHEN dictionary_entries.frequency > 0 THEN 0 ELSE 1 END,
                 dictionary_entries.frequency ASC,
                 LENGTH(dictionary_entries.expression) ASC
        LIMIT :limit
    """)
    fun searchByDefinition(
        query: String,
        language: String,
        limit: Int = 50
    ): Flow<List<DictionaryEntry>>

    /**
     * Corrects the language stamp on a freshly imported dictionary.
     *
     * Rows are written with the language active at import time, because ZIP
     * order does not guarantee index.json is read before the term banks. When
     * the index turns out to declare a `sourceLanguage` of its own, that is
     * the authoritative answer and this rewrites the batch.
     */
    @Query("UPDATE dictionary_entries SET language = :language WHERE dictionary_name = :dictionaryName")
    suspend fun updateLanguageForDictionary(dictionaryName: String, language: String)

    @Query("UPDATE dictionary_entries SET dictionary_name = :newName WHERE dictionary_name = :oldName")
    suspend fun updateDictionaryName(oldName: String, newName: String)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND frequency = 0")
    suspend fun updateFrequency(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression")
    suspend fun updateFrequencyForce(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND reading = :reading")
    suspend fun updateFrequencyWithReading(expression: String, reading: String, frequency: Int)

    // "Best rank" variants: only lower an existing rank (or fill a 0), so the
    // dictionary_entries.frequency column reflects the most-frequent rank
    // across ALL installed lists rather than whichever list imported last.
    // Per-list ranks live in the word_frequencies table.
    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND (frequency = 0 OR frequency > :frequency)")
    suspend fun updateFrequencyBest(expression: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET frequency = :frequency WHERE expression = :expression AND reading = :reading AND (frequency = 0 OR frequency > :frequency)")
    suspend fun updateFrequencyBestWithReading(expression: String, reading: String, frequency: Int)

    @Query("UPDATE dictionary_entries SET pitch_accent = :pitchAccent WHERE expression = :expression AND (pitch_accent = '' OR pitch_accent IS NULL)")
    suspend fun updatePitchAccent(expression: String, pitchAccent: String)

    /**
     * Scoped to one language, unlike its sibling above.
     *
     * The column carries Japanese pitch positions for Japanese rows and an
     * IPA transcription for the others, and plenty of words are spelled the
     * same in two languages ("no", "hotel", "final"). A user with both an
     * English and a Spanish dictionary installed would otherwise have the
     * second IPA import silently overwrite the first one's pronunciations.
     */
    @Query(
        "UPDATE dictionary_entries SET pitch_accent = :pitchAccent " +
            "WHERE expression = :expression AND language = :language"
    )
    suspend fun updatePitchAccentForce(expression: String, pitchAccent: String, language: String)

    @androidx.room.Transaction
    suspend fun updateFrequencyBatch(batch: List<FrequencyUpdate>) {
        for (update in batch) {
            val reading = update.reading?.trim().orEmpty()
            if (reading.isNotBlank()) {
                updateFrequencyBestWithReading(update.expression, reading, update.frequency)
            } else {
                updateFrequencyBest(update.expression, update.frequency)
            }
        }
    }

    @androidx.room.Transaction
    suspend fun updatePitchAccentBatch(batch: Map<String, String>, language: String) {
        for ((expression, pitchAccent) in batch) {
            updatePitchAccentForce(expression, pitchAccent, language)
        }
    }

    /**
     * Every entry a dictionary tagged with the given JLPT level. Feeds the
     * bulk JLPT deck generator; the ordering puts the most frequent words
     * first so a capped deck keeps the useful half.
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE jlpt_level = :level AND language = :language
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC
    """)
    suspend fun getEntriesByJlptLevel(level: Int, language: String): List<DictionaryEntry>

    /**
     * Every written form in the installed dictionaries.
     *
     * The text scanner needs the whole word list in memory at once: Japanese
     * has no spaces, so segmenting a subtitle file means testing every
     * substring of every sentence against the dictionary — hundreds of
     * thousands of lookups that cannot go through SQLite one at a time.
     */
    @Query("SELECT DISTINCT expression FROM dictionary_entries WHERE expression != '' AND language = :language")
    suspend fun getAllExpressions(language: String): List<String>

    /**
     * Readings that are also a way the word is actually WRITTEN: kana-only
     * headwords, and entries the dictionary marks "usually kana".
     *
     * Every reading used to go into the lexicon, on the reasoning that a text
     * writes 見る with kanji and みる without. What that really did was let any
     * stretch of kana match the reading of some kanji word: 俺の**こと** became
     * 鋸 (のこ, "saw"), で**はな**く became 出鼻, そう**にな**った became 担う —
     * dozens of cards per novel for words that never appear in it. A reading is
     * only a segmentation candidate when the dictionary itself says the word is
     * written that way.
     *
     * `uk` is JMdict's tag and lands in `parts_of_speech`; Jitendex writes the
     * same fact as a "(usually kana)" prefix on the gloss, which is still in
     * the stored definition text at this point.
     */
    @Query(
        """
        SELECT DISTINCT reading FROM dictionary_entries
        WHERE reading != '' AND language = :language
          AND (
            reading = expression
            OR (',' || REPLACE(parts_of_speech, ' ', '') || ',') LIKE '%,uk,%'
            OR definition LIKE '%usually kana%'
          )
        """
    )
    suspend fun getKanaWrittenReadings(language: String): List<String>

    /**
     * Exact-expression batch lookup. Callers MUST chunk the list well below
     * SQLite's 999-variable ceiling — see IN_CLAUSE_CHUNK in the repository.
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE expression IN (:expressions) AND language = :language
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC
    """)
    suspend fun getEntriesByExpressions(
        expressions: List<String>,
        language: String
    ): List<DictionaryEntry>

    /**
     * Batch counterpart of [getByReading]: resolves the kana words a text
     * writes without kanji (みる, ある) to their dictionary entries. Same
     * 999-variable chunking rule as [getEntriesByExpressions].
     */
    @Query("""
        SELECT * FROM dictionary_entries
        WHERE reading IN (:readings) AND language = :language
        ORDER BY CASE WHEN frequency > 0 THEN 0 ELSE 1 END, frequency ASC
    """)
    suspend fun getEntriesByReadings(
        readings: List<String>,
        language: String
    ): List<DictionaryEntry>

    // A word may be tagged by several sources, or belong to more than one
    // level in the same source. The lower tier wins (5 = N5 = easiest): the
    // word should be learned at the earliest level it appears in, which is
    // also the rule MergedWordEntry.mergeEntries applies when grouping.
    @Query(
        """
        UPDATE dictionary_entries SET jlpt_level = :level
        WHERE expression = :expression AND reading = :reading AND jlpt_level < :level
        """
    )
    suspend fun updateJlptLevelWithReading(expression: String, reading: String, level: Int)

    /**
     * Entries for a set of words from ONE dictionary. Feeds the monolingual
     * card engine, which must read the definition from a specific installed
     * dictionary rather than "whatever matched first".
     *
     * Callers MUST chunk below SQLite's 999-variable ceiling — see
     * IN_CLAUSE_CHUNK in the repository.
     */
    @Query(
        """
        SELECT * FROM dictionary_entries
        WHERE dictionary_name = :dictionaryName AND expression IN (:expressions)
        """
    )
    suspend fun getEntriesByExpressionsFromDictionary(
        expressions: List<String>,
        dictionaryName: String
    ): List<DictionaryEntry>

    @Query("UPDATE dictionary_entries SET jlpt_level = :level WHERE expression = :expression AND jlpt_level < :level")
    suspend fun updateJlptLevelByExpression(expression: String, level: Int)

    @androidx.room.Transaction
    suspend fun updateJlptLevelBatch(batch: List<JlptUpdate>) {
        for (update in batch) {
            val reading = update.reading?.trim().orEmpty()
            if (reading.isNotBlank()) {
                updateJlptLevelWithReading(update.expression, reading, update.level)
            } else {
                updateJlptLevelByExpression(update.expression, update.level)
            }
        }
    }

    /**
     * Re-applies every stored JLPT tag onto the term rows in one statement.
     *
     * This is what makes the JLPT data survive: term rows are deleted and
     * re-inserted on every re-import (losing their `jlpt_level`), and a meta
     * dictionary imported BEFORE its term dictionary has nothing to write to.
     * Running this after each import repairs both cases regardless of order.
     *
     * A tag row with an empty reading matches on the expression alone; MAX
     * picks the easiest level when several tags cover the same word.
     */
    @Query(
        """
        UPDATE dictionary_entries SET jlpt_level = MAX(jlpt_level, COALESCE((
            SELECT MAX(t.level) FROM jlpt_tags t
            WHERE t.expression = dictionary_entries.expression
              AND (t.reading = dictionary_entries.reading OR t.reading = '')
        ), 0))
        WHERE EXISTS (
            SELECT 1 FROM jlpt_tags t
            WHERE t.expression = dictionary_entries.expression
              AND (t.reading = dictionary_entries.reading OR t.reading = '')
        )
        """
    )
    suspend fun applyJlptLevelsFromTags()

    /**
     * Same idea for frequency: `dictionary_entries.frequency` is the best rank
     * across installed lists and is used for search ordering AND for the JLPT
     * deck's rarity filter, but it lives on rows a term re-import throws away.
     * `word_frequencies` keeps the real data, so roll it back down afterwards.
     */
    @Query(
        """
        UPDATE dictionary_entries SET frequency = COALESCE(
            (
                -- The leading list first, when the user named one and it knows
                -- the word. Several lists disagree by design — a word common in
                -- conversation is rare in print — so "best rank anywhere" made
                -- one generous list speak for all of them, on the card, in the
                -- search order and in the rarity filters.
                SELECT MIN(f.rank) FROM word_frequencies f
                WHERE :leadingDictionary != ''
                  AND f.dictionary = :leadingDictionary
                  AND f.expression = dictionary_entries.expression
                  AND (f.reading = dictionary_entries.reading OR f.reading = '')
                  AND f.rank > 0
            ),
            -- Strict: the leading list is the only source, so a word it does
            -- not know is unranked rather than borrowing another list's
            -- number. One scale on every card is what an Anki reorder addon
            -- needs — ranks from two lists interleaved sort into an order
            -- neither of them meant.
            CASE WHEN :strict = 1 AND :leadingDictionary != '' THEN 0 ELSE NULL END,
            (
                SELECT MIN(f.rank) FROM word_frequencies f
                WHERE f.expression = dictionary_entries.expression
                  AND (f.reading = dictionary_entries.reading OR f.reading = '')
                  AND f.rank > 0
            ),
            frequency
        )
        WHERE EXISTS (
            SELECT 1 FROM word_frequencies f
            WHERE f.expression = dictionary_entries.expression
              AND (f.reading = dictionary_entries.reading OR f.reading = '')
              AND f.rank > 0
        )
        """
    )
    suspend fun applyFrequenciesFromTable(leadingDictionary: String, strict: Int)
}
