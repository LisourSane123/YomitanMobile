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
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.dao.LookupCountDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.dao.SentenceDao
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryEntryFts
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.local.entity.LookupCount
import com.yomitanmobile.data.local.entity.SearchHistory
import com.yomitanmobile.data.local.entity.Sentence

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
        LookupCount::class
    ],
    version = 13,
    exportSchema = false
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

    companion object {
        const val DATABASE_NAME = "yomitan_mobile_db"

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
