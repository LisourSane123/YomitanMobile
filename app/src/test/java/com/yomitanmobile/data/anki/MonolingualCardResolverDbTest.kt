package com.yomitanmobile.data.anki

import com.yomitanmobile.data.settings.PreferenceKeys
import com.yomitanmobile.data.settings.LanguageSettings
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.repository.DictionaryRepositoryImpl
import com.yomitanmobile.dataStore
import com.yomitanmobile.domain.model.ExamplePair
import com.yomitanmobile.domain.model.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.yomitanmobile.data.settings.FrequencySettings

/**
 * The JP-JP card engine against a real (in-memory) dictionary: the setting is
 * read from DataStore exactly as it is in production, and the definitions come
 * out of a second installed dictionary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MonolingualCardResolverDbTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var resolver: MonolingualCardResolver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
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
        resolver = MonolingualCardResolver(repo, context, LanguageSettings(context))

        runBlocking {
            db.dictionaryDao().insertAll(
                listOf(
                    DictionaryEntry(
                        expression = "猫", reading = "ねこ",
                        definition = "[\"cat\"]", dictionaryName = "Jitendex"
                    ),
                    DictionaryEntry(
                        expression = "猫", reading = "ねこ",
                        definition = "[\"ネコ科の小形の哺乳類。\"]", dictionaryName = "三省堂"
                    ),
                    DictionaryEntry(
                        expression = "曖昧", reading = "あいまい",
                        definition = "[\"vague; ambiguous\"]", dictionaryName = "Jitendex"
                    ),
                    DictionaryEntry(
                        expression = "曖昧", reading = "あいまい",
                        definition = "[\"はっきりしないさま。\"]", dictionaryName = "旺文社国語辞典 第十一版"
                    ),
                    DictionaryEntry(
                        expression = "猫", reading = "ねこ",
                        definition = "[\"ネコ科ネコ属の動物。\"]", dictionaryName = "Pixiv Light [2025-12-11]"
                    ),
                    DictionaryEntry(
                        expression = "どきどき", reading = "どきどき",
                        definition = "[\"「ドキドキ」の表記揺れ\"]", dictionaryName = "Pixiv Light [2025-12-11]"
                    ),
                    DictionaryEntry(
                        expression = "どきどき", reading = "どきどき",
                        definition = "[\"激しく動悸のするさま。\"]", dictionaryName = "旺文社国語辞典 第十一版"
                    )
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun setEngine(language: String?, dictionary: String?) = runBlocking {
        context.dataStore.edit { prefs ->
            if (language == null) prefs.remove(PreferenceKeys.CARD_MEANING_LANGUAGE)
            else prefs[PreferenceKeys.CARD_MEANING_LANGUAGE] = language
            if (dictionary == null) prefs.remove(PreferenceKeys.CARD_MONOLINGUAL_DICTIONARY)
            else prefs[PreferenceKeys.CARD_MONOLINGUAL_DICTIONARY] = dictionary
        }
    }

    private fun word(expression: String, reading: String, definition: String) = WordEntry(
        id = 0,
        expression = expression,
        reading = reading,
        definitions = listOf(definition),
        exampleSentence = "猫が好きです。",
        exampleSentenceTranslation = "I like cats.",
        examples = listOf(ExamplePair(jp = "猫が好きです。", en = "I like cats."))
    )

    @Test
    fun englishEngineLeavesTheCardUntouched() = runBlocking {
        setEngine("EN", "三省堂")
        val result = resolver.apply(word("猫", "ねこ", "cat")).let { listOf(it) }.single()

        assertEquals(listOf("cat"), result.definitions)
        assertEquals("I like cats.", result.exampleSentenceTranslation)
    }

    @Test
    fun japaneseEngineSwapsInTheMonolingualDefinition() = runBlocking {
        setEngine("JA", "三省堂")
        val result = resolver.apply(word("猫", "ねこ", "cat"))

        assertEquals(listOf("ネコ科の小形の哺乳類。"), result.definitions)
    }

    @Test
    fun japaneseEngineDropsSentenceTranslationsButKeepsTheSentence() = runBlocking {
        setEngine("JA", "三省堂")
        val result = resolver.apply(word("猫", "ねこ", "cat"))

        assertEquals("猫が好きです。", result.exampleSentence)
        assertEquals("", result.exampleSentenceTranslation)
        assertEquals("猫が好きです。", result.examples.single().jp)
        assertEquals("", result.examples.single().en)
    }

    @Test
    fun wordsMissingFromTheMonolingualDictionaryKeepTheirEnglishDefinition() = runBlocking {
        // 曖昧 exists only in Jitendex here. A blank back would be worse than
        // an English one, so the English gloss stays.
        setEngine("JA", "三省堂")
        val result = resolver.apply(word("曖昧", "あいまい", "vague; ambiguous"))

        assertEquals(listOf("vague; ambiguous"), result.definitions)
    }

    @Test
    fun japaneseEngineWithoutAChosenDictionaryChangesNothing() = runBlocking {
        setEngine("JA", "")
        val result = resolver.apply(word("猫", "ねこ", "cat"))

        assertEquals(listOf("cat"), result.definitions)
        assertEquals("I like cats.", result.exampleSentenceTranslation)
    }

    @Test
    fun aBatchIsResolvedInOnePass() = runBlocking {
        setEngine("JA", "三省堂")
        val result = resolver.apply(
            listOf(word("猫", "ねこ", "cat"), word("曖昧", "あいまい", "vague; ambiguous"))
        )

        assertEquals(listOf("ネコ科の小形の哺乳類。"), result[0].definitions)
        assertEquals(listOf("vague; ambiguous"), result[1].definitions)
    }

    private val chain = "Pixiv Light [2025-12-11]\n旺文社国語辞典 第十一版"

    @Test
    fun theFirstDictionaryOnTheListThatHasTheWordSuppliesIt() = runBlocking {
        setEngine("JA", chain)
        val result = resolver.apply(
            listOf(
                word("猫", "ねこ", "cat"),
                word("曖昧", "あいまい", "vague; ambiguous"),
                word("学校", "がっこう", "school")
            )
        )

        // Pixiv has 猫, so 旺文社 is never asked for it.
        assertEquals(listOf("ネコ科ネコ属の動物。"), result[0].definitions)
        assertEquals("Pixiv Light [2025-12-11]", result[0].meaningSource)
        // Pixiv lacks 曖昧 here; the second dictionary has it.
        assertEquals(listOf("はっきりしないさま。"), result[1].definitions)
        assertEquals("旺文社国語辞典 第十一版", result[1].meaningSource)
        // Neither has 学校: English stays, and nothing marks it J-J.
        assertEquals(listOf("school"), result[2].definitions)
        assertEquals("", result[2].meaningSource)
    }

    @Test
    fun aSpellingPointerIsNotAMeaningAndTheNextDictionaryIsAsked() = runBlocking {
        setEngine("JA", chain)
        val result = resolver.apply(word("どきどき", "どきどき", "throb"))

        assertEquals(listOf("激しく動悸のするさま。"), result.definitions)
        assertEquals("旺文社国語辞典 第十一版", result.meaningSource)
    }

    @Test
    fun aSingleSavedNameStillReadsAsAListOfOne() {
        assertEquals(listOf("三省堂"), MonolingualCardResolver.parseDictionaryChain("三省堂"))
        assertEquals(emptyList<String>(), MonolingualCardResolver.parseDictionaryChain(""))
        assertEquals(
            listOf("A", "B"),
            MonolingualCardResolver.parseDictionaryChain(
                MonolingualCardResolver.serializeDictionaryChain(listOf("A", " B ", "A"))
            )
        )
    }

    @Test
    fun onlyAJapaneseMeaningPutsTheJJTagOnTheNote() = runBlocking {
        setEngine("JA", chain)
        val result = resolver.apply(listOf(word("猫", "ねこ", "cat"), word("学校", "がっこう", "school")))
        val base = setOf("yomitan-mobile")

        assertEquals(setOf("yomitan-mobile", "J-J"), AnkiCardCreator.tagsFor(result[0], base))
        assertEquals(base, AnkiCardCreator.tagsFor(result[1], base))
    }
}
