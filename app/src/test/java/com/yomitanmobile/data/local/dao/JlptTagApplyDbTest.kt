package com.yomitanmobile.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yomitanmobile.data.local.database.AppDatabase
import com.yomitanmobile.data.local.entity.DictionaryEntry
import com.yomitanmobile.data.local.entity.JlptTag
import com.yomitanmobile.data.local.entity.WordFrequency
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The JLPT deck generator is only as complete as the `jlpt_level` column, and
 * that column used to be written by updating already-present term rows. Two
 * everyday sequences silently emptied it — installing the tag dictionary
 * first, and re-importing a term dictionary — leaving the generator with zero
 * candidates. These tests pin the repair path: the tags live in their own
 * table and are re-applied onto whatever term rows currently exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JlptTagApplyDbTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DictionaryDao
    private lateinit var tags: JlptTagDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.dictionaryDao()
        tags = db.jlptTagDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entry(
        expression: String,
        reading: String,
        jlptLevel: Int = 0,
        frequency: Int = 0
    ) = DictionaryEntry(
        expression = expression,
        reading = reading,
        definition = "[\"meaning\"]",
        frequency = frequency,
        pitchAccent = "",
        partsOfSpeech = "n",
        dictionaryName = "Test",
        jlptLevel = jlptLevel
    )

    private fun levelOf(expression: String): Int = runBlocking {
        dao.getEntriesByJlptLevel(1).plus(dao.getEntriesByJlptLevel(2))
            .plus(dao.getEntriesByJlptLevel(3))
            .plus(dao.getEntriesByJlptLevel(4))
            .plus(dao.getEntriesByJlptLevel(5))
            .firstOrNull { it.expression == expression }?.jlptLevel ?: 0
    }

    @Test
    fun tagsStoredBeforeTheTermDictionaryStillLandOnIt() = runBlocking {
        // The user installs "JLPT Vocab Tags" first — nothing to update yet.
        tags.insertAll(listOf(JlptTag("食べる", "たべる", "jlpt", 5)))
        dao.applyJlptLevelsFromTags()

        // …and the term dictionary afterwards.
        dao.insertAll(listOf(entry("食べる", "たべる")))
        dao.applyJlptLevelsFromTags()

        assertEquals(5, levelOf("食べる"))
        assertEquals(1, dao.getEntriesByJlptLevel(5).size)
    }

    @Test
    fun reimportingATermDictionaryDoesNotLoseTheLevels() = runBlocking {
        dao.insertAll(listOf(entry("学校", "がっこう")))
        tags.insertAll(listOf(JlptTag("学校", "がっこう", "jlpt", 5)))
        dao.applyJlptLevelsFromTags()
        assertEquals(5, levelOf("学校"))

        // A re-import deletes the dictionary's rows and inserts fresh ones,
        // which carry jlpt_level = 0.
        dao.deleteByDictionary("Test")
        dao.insertAll(listOf(entry("学校", "がっこう")))
        assertEquals(0, levelOf("学校"))

        dao.applyJlptLevelsFromTags()
        assertEquals(5, levelOf("学校"))
    }

    @Test
    fun tagWithoutAReadingMatchesOnTheExpressionAlone() = runBlocking {
        dao.insertAll(listOf(entry("水", "みず")))
        tags.insertAll(listOf(JlptTag("水", "", "jlpt", 5)))
        dao.applyJlptLevelsFromTags()

        assertEquals(5, levelOf("水"))
    }

    @Test
    fun theEasiestLevelWinsWhenSourcesDisagree() = runBlocking {
        // A term dictionary tagged it N2; the tag list says N4. The word
        // should be learned at the earliest level it appears in.
        dao.insertAll(listOf(entry("以上", "いじょう", jlptLevel = 2)))
        tags.insertAll(listOf(JlptTag("以上", "いじょう", "jlpt", 4)))
        dao.applyJlptLevelsFromTags()

        assertEquals(4, levelOf("以上"))
    }

    @Test
    fun untaggedWordsAreLeftAlone() = runBlocking {
        dao.insertAll(listOf(entry("無関係", "むかんけい", jlptLevel = 0)))
        tags.insertAll(listOf(JlptTag("別の言葉", "べつのことば", "jlpt", 5)))
        dao.applyJlptLevelsFromTags()

        assertEquals(0, levelOf("無関係"))
    }

    @Test
    fun frequenciesAreRestoredOntoFreshlyImportedRows() = runBlocking {
        db.frequencyDao().insertAll(
            listOf(
                WordFrequency("学校", "がっこう", "JPDB", 812, "812"),
                WordFrequency("学校", "がっこう", "BCCWJ", 1500, "1500")
            )
        )
        // Rows a term (re-)import just wrote: no frequency of their own.
        dao.insertAll(listOf(entry("学校", "がっこう", frequency = 0)))
        dao.applyFrequenciesFromTable()

        val best = dao.getEntriesByExpressions(listOf("学校")).single().frequency
        assertEquals(812, best)
    }
}
