package com.yomitanmobile.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yomitanmobile.data.local.converter.Converters
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.dao.AnkiCollectionWordDao
import com.yomitanmobile.data.local.dao.JlptTagDao
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.dao.LookupCountDao
import com.yomitanmobile.data.local.dao.FrequencyDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.dao.SentenceDao
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryEntryFts
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.FrequencyListSetting
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.local.entity.AnkiCollectionWord
import com.yomitanmobile.data.local.entity.JlptTag
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.local.entity.LookupCount
import com.yomitanmobile.data.local.entity.SearchHistory
import com.yomitanmobile.data.local.entity.Sentence
import com.yomitanmobile.data.local.entity.WordFrequency

@Database(
    entities = [
        DictionaryEntry::class,
        DictionaryEntryFts::class,
        DictionaryInfo::class,
        ExportedWord::class,
        FavoriteWord::class,
        SearchHistory::class,
        KanjiEntry::class,
        Sentence::class,
        LookupCount::class,
        WordFrequency::class,
        FrequencyListSetting::class,
        JlptTag::class,
        AnkiCollectionWord::class
    ],
    version = 22,
    // Schema history is written to app/schemas/ (room.schemaLocation in
    // build.gradle.kts) and committed, so future migrations can be written
    // against — and tested against — the exact shipped schema.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun dictionaryInfoDao(): DictionaryInfoDao
    abstract fun exportedWordDao(): ExportedWordDao
    abstract fun favoriteWordDao(): FavoriteWordDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun kanjiDao(): KanjiDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun lookupCountDao(): LookupCountDao
    abstract fun frequencyDao(): FrequencyDao
    abstract fun jlptTagDao(): JlptTagDao
    abstract fun ankiCollectionWordDao(): AnkiCollectionWordDao

    companion object {
        const val DATABASE_NAME = "yomitan_mobile_db"

        /**
         * Frequency lists keep the numbers they shipped.
         *
         * 20→21 converted a list of occurrence counts into ranks IN PLACE, so
         * a list that said "seen 120 000 times" said "#3" on the card, in the
         * detail chips and on the frequency screen — a number no list ever
         * contained. This puts the shipped number back (it survived in
         * `display_value`), and moves the derived "where does it stand" into a
         * column of its own, `word_frequencies.position`, used only for
         * ordering and "Top N". `dictionary_entries.frequency_value` is filled
         * by the next frequency rollup, which the app runs once after this
         * migration (FrequencyRecomputer.ensureStorageCurrent).
         *
         * The ALTERs must match what Room generates for the entities verbatim.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `word_frequencies` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `dictionary_entries` ADD COLUMN `frequency_value` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    UPDATE word_frequencies SET rank = CAST(display_value AS INTEGER)
                    WHERE dictionary IN (SELECT dictionary FROM frequency_lists WHERE higher_is_better = 1)
                      AND display_value GLOB '[0-9]*' AND CAST(display_value AS INTEGER) > 0
                    """
                )
                val lists = mutableListOf<Pair<String, Boolean>>()
                db.query(
                    "SELECT w.dictionary, COALESCE(l.higher_is_better, 0) FROM " +
                        "(SELECT DISTINCT dictionary FROM word_frequencies) w " +
                        "LEFT JOIN frequency_lists l ON l.dictionary = w.dictionary"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        lists += cursor.getString(0) to (cursor.getInt(1) == 1)
                    }
                }
                lists.forEach { (name, higherIsBetter) ->
                    FrequencyPositions.recompute(db, name, higherIsBetter)
                }
            }
        }

        /**
         * Records what each frequency list's numbers mean.
         *
         * Every list until now was read as "lower is better", because that is
         * what a rank list ships. The lists built from raw occurrence counts
         * (Innocent Corpus and friends) run the other way, and were silently
         * telling the app that the commonest word in Japanese is the rarest
         * one. The new table holds one row per list; an install that has none
         * yet is classified the first time a list is imported or the frequency
         * screen is opened.
         *
         * The CREATE statement must match what Room generates for
         * [FrequencyListSetting] verbatim, or the identity check fails on open.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `frequency_lists` (" +
                        "`dictionary` TEXT NOT NULL, " +
                        "`higher_is_better` INTEGER NOT NULL, " +
                        "`auto_detected` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`dictionary`))"
                )
            }
        }

        /**
         * Makes the language part of the favourites / history identity.
         *
         * 18→19 gave both tables a language column but left the unique
         * indexes on (expression, reading) and (query). Both DAOs insert with
         * REPLACE, so starring a word that exists in another language's list
         * DELETED that row: the Spanish "no" quietly took the English one's
         * place, and the same for every shared spelling. Widening the indexes
         * cannot fail on existing data — the old constraint was the stricter
         * one, so no duplicate can already be present.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_favorite_words_expression_reading`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_favorite_words_expression_reading_language` " +
                        "ON `favorite_words` (`expression`, `reading`, `language`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_search_history_query`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_search_history_query_language` " +
                        "ON `search_history` (`query`, `language`)"
                )
            }
        }

        /**
         * Favorites and search history become per-language.
         *
         * They were the last user-facing tables with no language: after
         * switching to English you still saw your Japanese favourites, the
         * widget could pick one, and history offered queries that could no
         * longer match anything. Existing rows are Japanese for the same
         * reason dictionary rows are.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE favorite_words ADD COLUMN language TEXT NOT NULL DEFAULT 'ja'"
                )
                db.execSQL(
                    "ALTER TABLE search_history ADD COLUMN language TEXT NOT NULL DEFAULT 'ja'"
                )
            }
        }

        /**
         * Rebuilds the definition index with the `unicode61` tokenizer.
         *
         * The default `simple` tokenizer case-folds ASCII and nothing else,
         * which was invisible while every definition was English: "gad" and
         * "GAD" both matched, but "żaba" did not match a definition reading
         * "Żaba", and "año" only matched if typed with the tilde. That makes
         * meaning-search unreliable in exactly the two languages this app
         * gained — Polish glosses for English, English for Spanish.
         *
         * The CREATE statement must match what Room generates for the entity
         * (schemas/18.json) verbatim, or the identity check fails on open.
         * The rebuild re-reads every row from the content table, so it costs
         * one pass over the dictionary on first launch after upgrading and
         * nothing afterwards.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `dictionary_entries_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `dictionary_entries_fts` " +
                        "USING FTS4(`expression` TEXT NOT NULL, `reading` TEXT NOT NULL, " +
                        "`definition` TEXT NOT NULL, tokenize=unicode61, " +
                        "content=`dictionary_entries`)"
                )
                db.execSQL(
                    "INSERT INTO dictionary_entries_fts(dictionary_entries_fts) VALUES('rebuild')"
                )
            }
        }

        /**
         * Language becomes a first-class column.
         *
         * Everything already in the database predates multi-language support
         * and is therefore Japanese, which is why both DEFAULTs are 'ja' and
         * why AppLanguage.DEFAULT has to agree with them — an upgrading user
         * whose rows say 'ja' while the app filters on 'en' would open to an
         * empty dictionary.
         *
         * No index on the column on purpose: two values over hundreds of
         * thousands of rows is not selective enough to seek on, and every
         * query that carries the filter already narrows through the
         * expression / reading / FTS indexes first.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_entries ADD COLUMN language TEXT NOT NULL DEFAULT 'ja'"
                )
                db.execSQL(
                    "ALTER TABLE dictionaries ADD COLUMN language TEXT NOT NULL DEFAULT 'ja'"
                )
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cached AnkiDroid collection scan. Reading the provider takes
                // seconds on a real collection, so the duplicate check that
                // guards both mining and the JLPT generator reads this table
                // instead of rescanning per word.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS anki_collection_words (
                        word TEXT NOT NULL,
                        source TEXT NOT NULL,
                        scanned_at INTEGER NOT NULL,
                        PRIMARY KEY (word)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_anki_collection_words_source " +
                        "ON anki_collection_words(source)"
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Persistent JLPT tag storage — see the JlptTag entity. Until
                // now the level only existed as a column on term rows, so it
                // was lost whenever a term dictionary was re-imported and never
                // written at all when the tag dictionary was installed first.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS jlpt_tags (
                        expression TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        dictionary TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        PRIMARY KEY (expression, reading, dictionary)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_jlpt_tags_expression ON jlpt_tags(expression)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_jlpt_tags_expression_reading ON jlpt_tags(expression, reading)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_jlpt_tags_dictionary ON jlpt_tags(dictionary)")

                // Backfill from whatever levels the current rows still carry so
                // an existing install keeps its JLPT data (and survives the next
                // re-import). The source is unknown, hence the placeholder name.
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO jlpt_tags (expression, reading, dictionary, level)
                    SELECT expression, reading, 'imported', MAX(jlpt_level)
                    FROM dictionary_entries
                    WHERE jlpt_level > 0
                    GROUP BY expression, reading
                    """.trimIndent()
                )

                // The deck generator selects a whole level at once; without this
                // that is a full scan of every term row.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dictionary_entries_jlpt_level " +
                        "ON dictionary_entries(jlpt_level)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Per-source frequency storage. Previously every imported
                // frequency list overwrote a single dictionary_entries.frequency
                // column (last import won); now each list's rank is kept so the
                // UI can show them side by side in a user-chosen order. The
                // legacy `frequency` column survives as the "best rank" used for
                // search ordering. Composite PK matches @Entity(primaryKeys=…).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS word_frequencies (
                        expression TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        dictionary TEXT NOT NULL,
                        rank INTEGER NOT NULL,
                        display_value TEXT NOT NULL,
                        PRIMARY KEY (expression, reading, dictionary)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_word_frequencies_expression ON word_frequencies(expression)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_word_frequencies_expression_reading ON word_frequencies(expression, reading)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_word_frequencies_dictionary ON word_frequencies(dictionary)")
                // No backfill: the old single-column data has no source label
                // to attribute it to. Users re-import (or keep using) their
                // frequency lists to populate the new table.
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_entries ADD COLUMN examples_json TEXT NOT NULL DEFAULT ''"
                )
                // No backfill — only Jitendex (and similar enriched dicts) carry
                // examples. Users on plain JMDict simply have empty lists until
                // they re-import.
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Per-word lookup counter (feature: "if I keep coming
                // back to a rare word, prompt me to learn it"). Composite
                // PK on (expression, reading) — the entity declares the
                // same in @Entity(primaryKeys=…).
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lookup_counts (
                        expression TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        lookup_count INTEGER NOT NULL DEFAULT 1,
                        first_lookup INTEGER NOT NULL,
                        last_lookup INTEGER NOT NULL,
                        PRIMARY KEY (expression, reading)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Multi-label storage: a single ExportedWord can now belong
                // to multiple categories (CSV of WordCategoryClassifier
                // codes). Existing rows are backfilled from the legacy
                // single-value `export_category` so the stats rollup
                // keeps showing the same data until a reclassify pass
                // upgrades them with multi-label results.
                db.execSQL(
                    "ALTER TABLE exported_words ADD COLUMN export_categories TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    UPDATE exported_words
                    SET export_categories = COALESCE(NULLIF(TRIM(export_category), ''), 'OTHER')
                    WHERE export_categories = ''
                    """.trimIndent()
                )
                // User-set override for fix (I). Empty string means "no
                // override; respect classifier output". Survives any
                // future reclassify pass.
                db.execSQL(
                    "ALTER TABLE exported_words ADD COLUMN manual_category TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE dictionary_entries ADD COLUMN jlpt_level INTEGER NOT NULL DEFAULT 0"
                )
                // Backfill from parts_of_speech for dictionaries already imported.
                // Uses INSTR to locate "jlpt-N" tokens; the extra NOT LIKE check prevents
                // multi-digit false matches such as "jlpt-3000" matching jlpt-3.
                db.execSQL(
                    """
                    UPDATE dictionary_entries SET jlpt_level =
                        CASE
                            WHEN instr(lower(parts_of_speech), 'jlpt-1') > 0
                                 AND instr(lower(parts_of_speech), 'jlpt-10') = 0 THEN 1
                            WHEN instr(lower(parts_of_speech), 'jlpt-2') > 0
                                 AND instr(lower(parts_of_speech), 'jlpt-20') = 0 THEN 2
                            WHEN instr(lower(parts_of_speech), 'jlpt-3') > 0
                                 AND instr(lower(parts_of_speech), 'jlpt-30') = 0 THEN 3
                            WHEN instr(lower(parts_of_speech), 'jlpt-4') > 0
                                 AND instr(lower(parts_of_speech), 'jlpt-40') = 0 THEN 4
                            WHEN instr(lower(parts_of_speech), 'jlpt-5') > 0
                                 AND instr(lower(parts_of_speech), 'jlpt-50') = 0 THEN 5
                            ELSE 0
                        END
                    WHERE jlpt_level = 0
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE exported_words
                    ADD COLUMN export_hour INTEGER NOT NULL DEFAULT -1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE exported_words
                    SET export_hour = CAST(strftime('%H', export_date / 1000, 'unixepoch', 'localtime') AS INTEGER)
                    WHERE export_hour = -1
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE exported_words
                    ADD COLUMN export_category TEXT NOT NULL DEFAULT 'OTHER'
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create sentences table for offline local sentence storage
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sentences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        word_expression TEXT NOT NULL,
                        word_reading TEXT NOT NULL DEFAULT '',
                        sentence_japanese TEXT NOT NULL,
                        sentence_english TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'tatoeba'
                    )
                    """.trimIndent()
                )

                // Create indices for fast lookups
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sentences_word_expression ON sentences(word_expression)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sentences_word_reading ON sentences(word_reading)")

                // Seed with a small set of pre-seeded sentences
                seedSentences(db)
            }

            private fun seedSentences(db: SupportSQLiteDatabase) {
                val sentenceData = listOf(
                    arrayOf("こんにちは", "こんにちは", "こんにちは。", "Hello."),
                    arrayOf("ありがとう", "ありがとう", "ありがとうございました。", "Thank you very much."),
                    arrayOf("すみません", "すみません", "すみません、英語を教えてください。", "Excuse me, could you teach me English?"),
                    arrayOf("おはよう", "おはよう", "おはようございます。", "Good morning."),
                    arrayOf("さようなら", "さようなら", "さようなら。また明日。", "Goodbye. See you tomorrow."),
                    arrayOf("水", "みず", "水を一杯ください。", "Please give me a glass of water."),
                    arrayOf("食べる", "たべる", "私は毎日朝ご飯を食べます。", "I eat breakfast every morning."),
                    arrayOf("行く", "いく", "私は毎週図書館に行きます。", "I go to the library every week."),
                    arrayOf("見る", "みる", "映画を見に行きましょう。", "Let's go see a movie."),
                    arrayOf("聞く", "きく", "先生の説明を聞いてください。", "Please listen to the teacher's explanation."),
                    arrayOf("読む", "よむ", "毎日新聞を読みます。", "I read the newspaper every day."),
                    arrayOf("話す", "はなす", "彼はいつも日本語で話します。", "He always speaks in Japanese."),
                    arrayOf("起きる", "おきる", "毎朝6時に起きます。", "I wake up at 6 AM every morning."),
                    arrayOf("好き", "すき", "私は日本の文化が好きです。", "I like Japanese culture."),
                    arrayOf("犬", "いぬ", "うちの犬はとても元気です。", "Our dog is very energetic."),
                    arrayOf("猫", "ねこ", "猫が庭で遊んでいます。", "The cat is playing in the garden."),
                    arrayOf("冬", "ふゆ", "冬は雪が降ります。", "It snows in winter."),
                    arrayOf("春", "はる", "春は花が咲きます。", "Flowers bloom in spring."),
                    arrayOf("夏", "なつ", "夏は暑いです。", "It's hot in summer."),
                    arrayOf("秋", "あき", "秋は紅葉が美しいです。", "Autumn leaves are beautiful in fall."),
                    arrayOf("朝", "あさ", "朝日が出ています。", "The morning sun is rising."),
                    arrayOf("夜", "よる", "夜は星がきれいです。", "The stars are beautiful at night.")
                )

                sentenceData.forEach { row ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO sentences (word_expression, word_reading, sentence_japanese, sentence_english, source) VALUES (?, ?, ?, ?, 'tatoeba')",
                        row
                    )
                }
            }
        }
    }
}
