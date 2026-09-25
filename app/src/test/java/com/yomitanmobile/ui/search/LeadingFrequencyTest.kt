package com.yomitanmobile.ui.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.DictionaryInfo
import com.yomitanmobile.data.local.entity.WordFrequency
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.repository.DictionaryRepositoryImpl
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.AppLanguage
import com.yomitanmobile.domain.model.FrequencyTier
import com.yomitanmobile.domain.usecase.SearchDictionaryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every search result carries the leading list's number and its tier.
 *
 * The other half of the rule is here too: a word the leading list does NOT
 * rank shows nothing in the row, rather than borrowing the number of a list
 * that does. That was built once and taken out again — the user's call, and
 * the right one: "Top 3K" is a claim about how common a word is, the lists
 * disagree by design, and the word's own screen is where all of them belong.
 *
 * Written because caching that lookup broke it: the cached `StateFlow` was
 * built with `WhileSubscribed`, nothing ever collected it — the value is only
 * read through `.value` — so it stayed at its initial "no leading list" and
 * the frequency, and the "Top 5K" label with it, vanished from the whole
 * result list. Nothing in the suite noticed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LeadingFrequencyTest {

    // viewModelScope runs on Dispatchers.Main, and Robolectric's main looper is
    // the thread the test is blocking — without this the search pipeline never
    // gets to run and the test waits forever.
    private val dispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = DictionaryRepositoryImpl(
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
        db.dictionaryInfoDao().insert(DictionaryInfo(name = "JPDB", language = "ja"))
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(
                    expression = "食べる",
                    reading = "たべる",
                    definition = "[\"to eat\"]",
                    dictionaryName = "Jitendex",
                    language = "ja",
                    frequency = 300
                )
            )
        )
        db.frequencyDao().insertAll(
            listOf(
                WordFrequency(
                    expression = "食べる",
                    reading = "たべる",
                    dictionary = "JPDB",
                    rank = 421,
                    displayValue = "421",
                    position = 421
                )
            )
        )
        viewModel = SearchViewModel(
            searchDictionaryUseCase = SearchDictionaryUseCase(repo),
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
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a word the leading list does not rank shows no number and no tier`() =
        runTest(dispatcher) {
            // The user's own order decides who leads — not the alphabet, which
            // is how the lists come back from the database.
            FrequencySettings(ApplicationProvider.getApplicationContext())
                .setOrder(AppLanguage.JAPANESE, listOf("JPDB", "BCCWJ"))
            // BCCWJ ranks 洗濯; JPDB, the leading list, does not.
            db.dictionaryInfoDao().insert(DictionaryInfo(name = "BCCWJ", language = "ja"))
            db.dictionaryDao().insertAll(
                listOf(
                    DictionaryEntry(
                        expression = "洗濯",
                        reading = "せんたく",
                        definition = "[\"laundry\"]",
                        dictionaryName = "Jitendex",
                        language = "ja",
                        frequency = 0
                    )
                )
            )
            db.frequencyDao().insertAll(
                listOf(
                    WordFrequency(
                        expression = "洗濯",
                        reading = "せんたく",
                        dictionary = "BCCWJ",
                        rank = 4200,
                        displayValue = "4200",
                        position = 4200
                    )
                )
            )

            viewModel.onQueryChange("洗濯")
            val results = viewModel.searchResults.first { it.isNotEmpty() }
            // Nothing, on purpose: a tier is the leading list's claim to make,
            // and another list's number in that spot would pass for it. The
            // other lists are on the word's own screen, each named.
            assertNull(
                "another list's number reached the result row",
                results.first().leadingFrequency
            )
        }

    @Test
    fun `a result carries the leading list's number and its tier`() = runTest(dispatcher) {
        viewModel.onQueryChange("食べる")
        val results = viewModel.searchResults.first { it.isNotEmpty() }
        val entry = results.first()
        val leading = entry.leadingFrequency
        assertNotNull("the leading list's number never reached the result", leading)
        assertEquals("JPDB", leading!!.dictionary)
        // A rank reads as "#421"; a count-based list would read "421×".
        assertEquals("#421", leading.value())
        // …and the tier that the list's own position puts the word in, which is
        // what the row shows above the number.
        assertEquals("★★★ Top 1K", FrequencyTier.label(leading.position, leading.value()))
    }
}
