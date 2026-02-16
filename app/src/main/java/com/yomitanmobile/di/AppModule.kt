package com.yomitanmobile.di

import android.content.Context
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAnkiCardCreator(
        @ApplicationContext context: Context
    ): AnkiCardCreator {
        return AnkiCardCreator(context)
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(
        @ApplicationContext context: Context
    ): AudioPlayer {
        return AudioPlayer(context)
    }

    @Provides
    @Singleton
    fun provideDictionaryDownloadManager(
        @ApplicationContext context: Context,
        repository: DictionaryRepository
    ): DictionaryDownloadManager {
        return DictionaryDownloadManager(context, repository)
    }
}
