package com.yomitanmobile.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yomitanmobile.data.local.dao.DictionaryDao
import com.yomitanmobile.data.local.dao.DictionaryInfoDao
import com.yomitanmobile.data.local.dao.ExportedWordDao
import com.yomitanmobile.data.local.dao.FavoriteWordDao
import com.yomitanmobile.data.local.dao.SearchHistoryDao
import com.yomitanmobile.data.local.dao.KanjiDao
import com.yomitanmobile.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    ensureExportedWordsColumns(db)
                }
            })

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            builder.fallbackToDestructiveMigration()
        }

        return builder.build()
    }

    private fun ensureExportedWordsColumns(db: SupportSQLiteDatabase) {
        try {
            val existingColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(exported_words)").use { cursor ->
                val nameColumnIndex = cursor.getColumnIndex("name")
                if (nameColumnIndex >= 0) {
                    while (cursor.moveToNext()) {
                        existingColumns += cursor.getString(nameColumnIndex)
                    }
                }
            }

            if ("export_hour" !in existingColumns) {
                db.execSQL(
                    "ALTER TABLE exported_words ADD COLUMN export_hour INTEGER NOT NULL DEFAULT -1"
                )
                db.execSQL(
                    """
                    UPDATE exported_words
                    SET export_hour = CAST(strftime('%H', export_date / 1000, 'unixepoch', 'localtime') AS INTEGER)
                    WHERE export_hour = -1
                    """.trimIndent()
                )
            }

            if ("export_category" !in existingColumns) {
                db.execSQL(
                    "ALTER TABLE exported_words ADD COLUMN export_category TEXT NOT NULL DEFAULT 'OTHER'"
                )
            }
        } catch (_: Exception) {
            // If legacy schema repair fails, keep startup non-fatal and rely on existing error handling.
        }
    }

    @Provides
    @Singleton
    fun provideDictionaryDao(database: AppDatabase): DictionaryDao {
        return database.dictionaryDao()
    }

    @Provides
    @Singleton
    fun provideDictionaryInfoDao(database: AppDatabase): DictionaryInfoDao {
        return database.dictionaryInfoDao()
    }

    @Provides
    @Singleton
    fun provideExportedWordDao(database: AppDatabase): ExportedWordDao {
        return database.exportedWordDao()
    }

    @Provides
    @Singleton
    fun provideFavoriteWordDao(database: AppDatabase): FavoriteWordDao {
        return database.favoriteWordDao()
    }

    @Provides
    @Singleton
    fun provideSearchHistoryDao(database: AppDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }

    @Provides
    @Singleton
    fun provideKanjiDao(database: AppDatabase): KanjiDao {
        return database.kanjiDao()
    }
}
