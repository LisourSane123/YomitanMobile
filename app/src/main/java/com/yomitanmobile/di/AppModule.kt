package com.yomitanmobile.di

import android.content.Context
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.download.DictionaryDownloadManager
import com.yomitanmobile.data.repository.BackgroundWorkStarter
import com.yomitanmobile.service.BackgroundWorkService
import com.yomitanmobile.domain.repository.DictionaryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAnkiCardCreator(
        @ApplicationContext context: Context,
        languageSettings: com.yomitanmobile.data.settings.LanguageSettings,
        audioArchive: com.yomitanmobile.data.audio.AudioArchive,
        voicevox: com.yomitanmobile.data.audio.voicevox.VoicevoxVoice
    ): AnkiCardCreator {
        return AnkiCardCreator(context, languageSettings, audioArchive, voicevox)
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(
        @ApplicationContext context: Context,
        languageSettings: com.yomitanmobile.data.settings.LanguageSettings
    ): AudioPlayer {
        return AudioPlayer(context, languageSettings)
    }

    /**
     * The scope background work lives in: it belongs to the application, so a
     * dictionary install is not cancelled by leaving the screen that started
     * it. SupervisorJob keeps one failed install from taking the rest down.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keeps the process alive while long work runs with the app minimised. */
    @Provides
    @Singleton
    fun provideBackgroundWorkStarter(
        @ApplicationContext context: Context
    ): BackgroundWorkStarter = BackgroundWorkStarter { BackgroundWorkService.start(context) }

    @Provides
    @Singleton
    fun provideDictionaryDownloadManager(
        @ApplicationContext context: Context,
        repository: DictionaryRepository,
        applicationScope: CoroutineScope,
        backgroundWork: BackgroundWorkStarter
    ): DictionaryDownloadManager {
        return DictionaryDownloadManager(context, repository, applicationScope, backgroundWork)
    }
}
