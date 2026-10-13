package com.yomitanmobile.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.anki.WordForms
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.parser.YomitanDictionaryParser
import com.yomitanmobile.data.settings.FrequencySettings
import com.yomitanmobile.data.settings.LanguageSettings
import com.yomitanmobile.domain.model.MergedWordEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "The deck generator made cards for 綺麗 and 傷つく, which I already have."
 *
 * JMdict files every spelling of a word under one sequence — 傷つく, 傷付く,
 * 疵つく are one entry; 綺麗 and 奇麗 are another — while `mergeEntries` groups
 * by (expression, reading), so each spelling becomes its own MergedWordEntry
 * that knows nothing about its siblings. The duplicate check compared one
 * headword against the collection and reported "you do not have it" for a word
 * the user had been studying for months.
 *
 * The spellings are a fact about the database, so they are read from it
 * (`getEntriesForReadings` → [WordForms.Lookup]). This pins both halves of
 * that lookup: the siblings are found, and homophones —
 * which share a reading but NOT a sequence — are still kept apart. A false
 * "you already know this" silently drops a word from every future deck, which
 * is the one failure mode the scan is not allowed to have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WordFormsDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DictionaryRepositoryImpl

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = DictionaryRepositoryImpl(
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
        runBlocking {
            db.dictionaryDao().insertAll(
                listOf(
                    row("傷つく", "きずつく", 1234),
                    row("傷付く", "きずつく", 1234),
                    row("疵つく", "きずつく", 1234),
                    row("綺麗", "きれい", 5678),
                    row("奇麗", "きれい", 5678),
                    // Homophones: same reading, different words, different
                    // sequences. Owning one must never vouch for the other.
                    row("公園", "こうえん", 1111),
                    row("講演", "こうえん", 2222),
                    // A dictionary that ships no sequence at all.
                    row("手作り", "てづくり", 0)
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun row(expression: String, reading: String, sequence: Int) = DictionaryEntry(
        expression = expression,
        reading = reading,
        definition = "[\"gloss\"]",
        sequenceNumber = sequence,
        dictionaryName = "JMdict"
    )

    private suspend fun formsOf(expression: String, reading: String): WordForms {
        val rows = repo.getEntriesForReadings(listOf(reading))
        val entry = MergedWordEntry.mergeEntries(rows.filter { it.expression == expression }).single()
        return WordForms.Lookup(rows).of(entry)
    }

    @Test
    fun `every other spelling of one entry comes back`() = runBlocking {
        assertEquals(listOf("傷付く", "疵つく"), formsOf("傷つく", "きずつく").otherSpellings)
    }

    @Test
    fun `the other kanji spelling of 綺麗 is one of its forms`() = runBlocking {
        assertEquals(listOf("奇麗"), formsOf("綺麗", "きれい").otherSpellings)
    }

    @Test
    fun `homophones stay apart because they do not share a sequence`() = runBlocking {
        val forms = formsOf("公園", "こうえん")
        assertEquals(emptyList<String>(), forms.otherSpellings)
        // 講演 is another word read こうえん, and no list ranks either, so a
        // deck's kana-only こうえん cannot be taken for 公園.
        assertFalse(forms.readingIsUnambiguous)
    }

    @Test
    fun `an entry with no sequence contributes nothing rather than a guess`() = runBlocking {
        assertEquals(WordForms.NONE, formsOf("手作り", "てづくり"))
    }
}
