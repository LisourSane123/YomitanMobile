package com.yomitanmobile.ui.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.repository.DictionaryRepositoryImpl
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A word shared into the app is an EVENT, and an event is consumed once.
 *
 * The search screen applies it from a `LaunchedEffect`, and that effect runs
 * again every time the Search destination is entered — leaving for Settings
 * disposes the composable, coming back builds it anew — while the Activity
 * goes on offering the same query for as long as it lives. So the old shared
 * word came back and overwrote whatever was in the search box on every return
 * to the tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            jlptTagDao = db.jlptTagDao(),
            parser = YomitanDictionaryParser(),
            database = db,
            languageSettings = LanguageSettings(context),
            frequencySettings = FrequencySettings(context)
        )
        viewModel = SearchViewModel(
            searchDictionaryUseCase = SearchDictionaryUseCase(repository),
            dictionaryDao = db.dictionaryDao(),
            searchHistoryDao = db.searchHistoryDao(),
            exportedWordDao = db.exportedWordDao(),
            frequencyDao = db.frequencyDao(),
            frequencySettings = FrequencySettings(context),
            appContext = context,
            languageSettings = LanguageSettings(context)
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a shared word is applied once, not again on every return to the screen`() {
        // The share arrives and lands in the box.
        viewModel.applyExternalQuery("食べる", nonce = 0)
        assertEquals("食べる", viewModel.query.value)

        // The user looks something else up.
        viewModel.onQueryChange("飲む")

        // Settings and back: the screen is rebuilt and its effect runs again
        // with the same share the Activity is still holding.
        viewModel.applyExternalQuery("食べる", nonce = 0)
        assertEquals("飲む", viewModel.query.value)

        // …and again, and after the box was cleared.
        viewModel.clearQuery()
        viewModel.applyExternalQuery("食べる", nonce = 0)
        assertEquals("", viewModel.query.value)
    }

    @Test
    fun `sharing the same word a second time does apply it`() {
        viewModel.applyExternalQuery("食べる", nonce = 0)
        viewModel.onQueryChange("飲む")
        // A new share of the same text: a new event, so a new nonce.
        viewModel.applyExternalQuery("食べる", nonce = 1)
        assertEquals("食べる", viewModel.query.value)
    }

    @Test
    fun `the keyboard is asked for once, at the launch that asked for it`() {
        assertTrue(viewModel.consumeFocusRequest())
        // Every later entry into the tab asks again and is told no.
        assertFalse(viewModel.consumeFocusRequest())
        assertFalse(viewModel.consumeFocusRequest())
    }

    @Test
    fun `the rule itself`() {
        assertTrue(SearchViewModel.isNewExternalQuery(lastNonce = null, nonce = 0))
        assertFalse(SearchViewModel.isNewExternalQuery(lastNonce = 0, nonce = 0))
        assertTrue(SearchViewModel.isNewExternalQuery(lastNonce = 0, nonce = 1))
    }
}
