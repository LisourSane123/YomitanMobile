package com.yomitanmobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.yomitanmobile.MainActivity
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The active study language, cached in memory.
 *
 * Every search query filters on the language column, so the value has to be
 * readable synchronously from the data layer — a suspend read per keystroke
 * would put a DataStore round trip in front of the debounce. The cache is
 * primed once in `YomitanMobileApp.onCreate` and only ever changes through
 * [setLanguage], which is followed by a full process restart, so it cannot
 * drift from what the database rows were filtered against.
 */
@Singleton
class LanguageSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    private var cached: AppLanguage = AppLanguage.DEFAULT

    /** Read this from anything that runs per query. */
    val current: AppLanguage
        get() = cached

    /**
     * Whether the user has ever picked a language. False means first run and
     * sends the app to the language screen; it is NOT the same as "the
     * language is Japanese", which is also what an upgrading install reports
     * for [current].
     */
    suspend fun isConfigured(): Boolean =
        context.dataStore.data.first()[MainActivity.APP_LANGUAGE] != null

    val languageFlow: Flow<AppLanguage> = context.dataStore.data
        .map { AppLanguage.fromStorage(it[MainActivity.APP_LANGUAGE]) }

    /** Primes [cached]. Called once from Application.onCreate. */
    fun loadBlocking() {
        cached = runCatching {
            runBlocking {
                AppLanguage.fromStorage(context.dataStore.data.first()[MainActivity.APP_LANGUAGE])
            }
        }.getOrDefault(AppLanguage.DEFAULT)
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { prefs ->
            prefs[MainActivity.APP_LANGUAGE] = language.storageValue
        }
        cached = language
    }
}
