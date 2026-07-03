package com.yomitanmobile.ui.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.ai.AiSummaryService
import com.yomitanmobile.data.anki.AnkiCardCreator
import com.yomitanmobile.data.audio.AudioPlayer
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.repository.DictionaryRepositoryImpl
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.usecase.GetWordDetailUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DetailViewModelFuriganaTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DictionaryRepositoryImpl(
            dictionaryDao = db.dictionaryDao(),
            dictionaryInfoDao = db.dictionaryInfoDao(),
            kanjiDao = db.kanjiDao(),
            frequencyDao = db.frequencyDao(),
            parser = YomitanDictionaryParser(),
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun buildViewModel(entryId: Long) = DetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("entryId" to entryId)),
        getWordDetailUseCase = GetWordDetailUseCase(repo),
        repository = repo,
        ankiCardCreator = AnkiCardCreator(context),
        audioPlayer = AudioPlayer(context),
        sentenceDao = db.sentenceDao(),
        aiSummaryService = AiSummaryService(),
        exportedWordDao = db.exportedWordDao(),
        favoriteWordDao = db.favoriteWordDao(),
        lookupCountDao = db.lookupCountDao(),
        appContext = context
    )

    @Test
    fun viewModelPopulatesGeneratedFuriganaForNoRubyExample() = runBlocking {
        // The headword entry, carrying a NO-RUBY example sentence (segments
        // empty) — exactly the pre-ruby / plain import case.
        val sentence = "私は寿司を食べた。"
        val examplesJson = Json.encodeToString(
            ListSerializer(ExamplePair.serializer()),
            listOf(ExamplePair(jp = sentence, en = "I ate sushi.", segments = emptyList()))
        )
        db.dictionaryDao().insertAll(
            listOf(
                DictionaryEntry(
                    id = 1, expression = "寿司", reading = "すし",
                    definition = "[\"sushi\"]", dictionaryName = "Test",
                    examplesJson = examplesJson
                ),
                // Words the fallback needs to resolve the sentence:
                DictionaryEntry(id = 2, expression = "私", reading = "わたし", definition = "[]", frequency = 10, dictionaryName = "Test"),
                DictionaryEntry(id = 3, expression = "食べる", reading = "たべる", definition = "[]", dictionaryName = "Test")
            )
        )

        val vm = buildViewModel(1L)

        // loadEntry / loadExampleFurigana suspend on Room's background executor,
        // so poll (idling the main looper) until furigana resolves or we time out.
        val deadline = System.currentTimeMillis() + 5000
        while (vm.generatedFurigana.value.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }

        val map = vm.generatedFurigana.value
        assertTrue("generatedFurigana should be populated, was $map", map.isNotEmpty())
        val segs = map[sentence].orEmpty()
        assertTrue("sentence should have synthesised segments: $segs", segs.isNotEmpty())
        assertTrue("寿司→すし tappable: $segs", segs.any { it.text == "寿司" && it.reading == "すし" })
        assertTrue("食→た tappable (conjugated): $segs", segs.any { it.text == "食" && it.reading == "た" })
        assertTrue("私→わたし tappable: $segs", segs.any { it.text == "私" && it.reading == "わたし" })
    }
}
