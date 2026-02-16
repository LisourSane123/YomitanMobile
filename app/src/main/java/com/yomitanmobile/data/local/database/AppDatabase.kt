package com.yomitanmobile.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yomitanmobile.data.local.converter.Converters
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryEntryFts
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.ExportedWord
import com.yomitanmobile.data.local.entity.SearchHistory

@Database(
    entities = [
        DictionaryEntry::class,
        DictionaryEntryFts::class,
        DictionaryInfo::class,
        ExportedWord::class,
        SearchHistory::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun dictionaryInfoDao(): DictionaryInfoDao
    abstract fun exportedWordDao(): ExportedWordDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        const val DATABASE_NAME = "yomitan_mobile_db"
    }
}
