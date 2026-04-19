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
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryEntryFts
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.local.entity.KanjiEntry
import com.yomitanmobile.data.local.entity.SearchHistory

@Database(
    entities = [
        DictionaryEntry::class,
        DictionaryEntryFts::class,
        DictionaryInfo::class,
        ExportedWord::class,
        FavoriteWord::class,
        SearchHistory::class,
        KanjiEntry::class
    ],
    version = 8,
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

    companion object {
        const val DATABASE_NAME = "yomitan_mobile_db"

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
    }
}
