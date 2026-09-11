package com.yomitanmobile.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.FavoriteWord
import com.yomitanmobile.data.local.entity.SearchHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Favourites and history belong to one language each.
 *
 * The tables gained a `language` column in 18→19 but kept unique indexes on
 * (expression, reading) and (query) alone. Both DAOs insert with REPLACE, so
 * starring a word that another language already had DELETED that row — "no",
 * "hotel" and "final" are words in more than one of the languages this app
 * teaches, and the user simply watched a favourite disappear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerLanguageUserDataDbTest {

    private lateinit var db: AppDatabase
    private lateinit var favorites: FavoriteWordDao
    private lateinit var history: SearchHistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        favorites = db.favoriteWordDao()
        history = db.searchHistoryDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the same spelling can be a favourite in two languages`() = runBlocking {
        favorites.insert(FavoriteWord(expression = "no", reading = "no", language = "en"))
        favorites.insert(FavoriteWord(expression = "no", reading = "no", language = "es"))

        assertEquals(1, favorites.getCount("en"))
        assertEquals(1, favorites.getCount("es"))
        assertEquals(true, favorites.isFavoriteSync("no", "no", "en"))
        assertEquals(true, favorites.isFavoriteSync("no", "no", "es"))
    }

    @Test
    fun `re-starring the same word in one language still replaces its row`() = runBlocking {
        favorites.insert(FavoriteWord(expression = "no", reading = "no", language = "en"))
        favorites.insert(
            FavoriteWord(expression = "no", reading = "no", language = "en", definitionPreview = "not")
        )

        assertEquals(1, favorites.getCount("en"))
        assertEquals("not", favorites.getAllFavorites("en").first().single().definitionPreview)
    }

    @Test
    fun `clearing favourites leaves the other language alone`() = runBlocking {
        favorites.insert(FavoriteWord(expression = "犬", reading = "いぬ", language = "ja"))
        favorites.insert(FavoriteWord(expression = "dog", reading = "dog", language = "en"))

        favorites.deleteAll("en")

        assertEquals(1, favorites.getCount("ja"))
        assertEquals(0, favorites.getCount("en"))
    }

    @Test
    fun `the same query can sit in two languages' history`() = runBlocking {
        history.insert(SearchHistory(query = "final", language = "en"))
        history.insert(SearchHistory(query = "final", language = "es"))

        assertEquals(1, history.getCount("en"))
        assertEquals(1, history.getCount("es"))
    }

    @Test
    fun `clearing history leaves the other language alone`() = runBlocking {
        history.insert(SearchHistory(query = "犬", language = "ja"))
        history.insert(SearchHistory(query = "dog", language = "en"))

        history.deleteAll("en")

        assertEquals(1, history.getCount("ja"))
        assertEquals(0, history.getCount("en"))
    }
}
