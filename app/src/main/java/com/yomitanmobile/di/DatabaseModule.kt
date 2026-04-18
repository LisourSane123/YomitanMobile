package com.yomitanmobile.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
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

        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            builder.fallbackToDestructiveMigration()
        }

        return builder.build()
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
