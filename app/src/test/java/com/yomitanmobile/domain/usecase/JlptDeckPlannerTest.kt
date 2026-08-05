package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.JlptDeckFilters
import com.yomitanmobile.domain.model.JlptSkipReason
import com.yomitanmobile.domain.model.MergedWordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JlptDeckPlannerTest {

    private fun entry(
        expression: String,
        reading: String = expression,
        frequency: Int = 100,
        definitions: List<String> = listOf("meaning"),
        usageTags: List<String> = emptyList(),
        partsOfSpeech: List<String> = listOf("n"),
        dictionaryName: String = "Jitendex"
    ) = MergedWordEntry(
        primaryId = 0,
        primaryExpression = expression,
        reading = reading,
        definitions = definitions,
        alternativeExpressions = emptyList(),
        frequency = frequency,
        partsOfSpeech = partsOfSpeech,
        dictionaryName = dictionaryName,
        usageTags = usageTags
    )

    @Test
    fun keepsCommonWordsAndDropsTheRareTail() {
        val plan = JlptDeckPlanner.plan(
            level = 4,
            candidates = listOf(
                entry("食べる", "たべる", frequency = 300),
                entry("茫漠", "ぼうばく", frequency = 84_000)
            ),
            filters = JlptDeckFilters(maxFrequencyRank = 30_000)
        )

        assertEquals(listOf("食べる"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.TOO_RARE])
    }

    @Test
    fun unrankedWordsSurviveByDefault() {
        val plan = JlptDeckPlanner.plan(
            level = 3,
            candidates = listOf(entry("面白い", "おもしろい", frequency = 0)),
            filters = JlptDeckFilters()
        )

        assertEquals(1, plan.selectedCount)
    }

    @Test
    fun unrankedWordsCanBeDroppedExplicitly() {
        val plan = JlptDeckPlanner.plan(
            level = 3,
            candidates = listOf(entry("面白い", "おもしろい", frequency = 0)),
            filters = JlptDeckFilters(includeUnranked = false)
        )

        assertEquals(0, plan.selectedCount)
        assertEquals(1, plan.skipped[JlptSkipReason.UNRANKED])
    }

    @Test
    fun dropsArchaicAndObsoleteEntries() {
        val plan = JlptDeckPlanner.plan(
            level = 1,
            candidates = listOf(
                entry("いと", frequency = 500, usageTags = listOf("archaic")),
                entry("けり", frequency = 700, partsOfSpeech = listOf("aux-v", "arch")),
                entry("政治", "せいじ", frequency = 400)
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(listOf("政治"), plan.selected.map { it.primaryExpression })
        assertEquals(2, plan.skipped[JlptSkipReason.ARCHAIC])
    }

    @Test
    fun dropsProperNamesButKeepsOrdinaryWordsFromNormalDictionaries() {
        val plan = JlptDeckPlanner.plan(
            level = 2,
            candidates = listOf(
                entry("田中", "たなか", partsOfSpeech = listOf("surname"), dictionaryName = "JMnedict"),
                entry("東京", "とうきょう", partsOfSpeech = listOf("place", "n"))
            ),
            filters = JlptDeckFilters()
        )

        // 東京 keeps a normal noun tag alongside "place", so it stays.
        assertEquals(listOf("東京"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.PROPER_NAME])
    }

    @Test
    fun skipsWordsAlreadyInAnkiAndAlreadyMined() {
        val plan = JlptDeckPlanner.plan(
            level = 5,
            candidates = listOf(
                entry("食べる", "たべる"),
                entry("飲む", "のむ"),
                entry("見る", "みる")
            ),
            filters = JlptDeckFilters(),
            isInAnki = { it.primaryExpression == "食べる" },
            isMined = { it.primaryExpression == "飲む" }
        )

        assertEquals(listOf("見る"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.ALREADY_IN_ANKI])
        assertEquals(1, plan.skipped[JlptSkipReason.ALREADY_MINED])
    }

    @Test
    fun capKeepsTheMostFrequentWords() {
        val plan = JlptDeckPlanner.plan(
            level = 3,
            candidates = listOf(
                entry("稀語", frequency = 9_000),
                entry("普通", frequency = 500),
                entry("中間", frequency = 2_000)
            ),
            filters = JlptDeckFilters(maxWords = 2)
        )

        assertEquals(listOf("普通", "中間"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.OVER_LIMIT])
    }

    @Test
    fun entriesWithoutDefinitionsNeverBecomeCards() {
        val plan = JlptDeckPlanner.plan(
            level = 5,
            candidates = listOf(entry("空", definitions = listOf("", "  "))),
            filters = JlptDeckFilters()
        )

        assertEquals(0, plan.selectedCount)
        assertEquals(1, plan.skipped[JlptSkipReason.NO_DEFINITION])
    }

    @Test
    fun eachSkippedWordIsCountedExactlyOnce() {
        val plan = JlptDeckPlanner.plan(
            level = 1,
            candidates = listOf(
                entry("古語", frequency = 90_000, usageTags = listOf("archaic")),
                entry("普通", frequency = 100)
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(plan.candidateCount, plan.selectedCount + plan.skippedCount)
        assertTrue(plan.skipped.values.all { it > 0 })
    }

    /**
     * The real shape of the data: MergedWordEntry.partsOfSpeech holds ONE
     * joined tag string per source entry ("n, v5r, arch"), not one token per
     * element. Matching whole strings against the tag sets never fired, so
     * every archaism sailed into the deck.
     */
    @Test
    fun archaicTagsAreFoundInsideJoinedPartOfSpeechStrings() {
        val plan = JlptDeckPlanner.plan(
            level = 1,
            candidates = listOf(
                entry("けり", partsOfSpeech = listOf("aux-v, arch")),
                entry("侍り", partsOfSpeech = listOf("v4r", "arch, obs")),
                entry("政治", "せいじ", partsOfSpeech = listOf("n, vs, adj-no"))
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(listOf("政治"), plan.selected.map { it.primaryExpression })
        assertEquals(2, plan.skipped[JlptSkipReason.ARCHAIC])
    }

    @Test
    fun properNameTagsAreFoundInsideJoinedPartOfSpeechStrings() {
        val plan = JlptDeckPlanner.plan(
            level = 2,
            candidates = listOf(
                entry("田中", "たなか", partsOfSpeech = listOf("surname, given"), dictionaryName = "JMnedict"),
                entry("東京", "とうきょう", partsOfSpeech = listOf("place, n"))
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(listOf("東京"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.PROPER_NAME])
    }

    /**
     * A merged entry carries a single dictionary name for the whole group, so
     * an ordinary word that JMnedict happens to also list must not be dropped
     * on the strength of that name alone — its real tags decide.
     */
    @Test
    fun ordinaryWordGroupedWithANameDictionaryRowSurvives() {
        val plan = JlptDeckPlanner.plan(
            level = 3,
            candidates = listOf(
                entry("空", "そら", partsOfSpeech = listOf("n", "surname"), dictionaryName = "JMnedict"),
                entry("大阪", "おおさか", partsOfSpeech = emptyList(), dictionaryName = "JMnedict")
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(listOf("空"), plan.selected.map { it.primaryExpression })
        assertEquals(1, plan.skipped[JlptSkipReason.PROPER_NAME])
    }

    @Test
    fun rankedWordsAreOrderedBeforeUnrankedOnes() {
        val plan = JlptDeckPlanner.plan(
            level = 4,
            candidates = listOf(
                entry("無ランク", frequency = 0),
                entry("頻出", frequency = 50)
            ),
            filters = JlptDeckFilters()
        )

        assertEquals(listOf("頻出", "無ランク"), plan.selected.map { it.primaryExpression })
    }
}
