package com.yomitanmobile.ui.statistics

import com.yomitanmobile.data.local.dao.HourlyActivityCount
import com.yomitanmobile.data.local.entity.ExportedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsViewModelWeeklyWordsTest {

    @Test
    fun toWeeklyLearnedWords_deduplicatesByExpressionAndReading() {
        val exports = listOf(
            ExportedWord(
                id = 1,
                expression = "食べる",
                reading = "たべる",
                deckName = "DeckA",
                exportDate = 100L
            ),
            ExportedWord(
                id = 2,
                expression = "食べる",
                reading = "たべる",
                deckName = "DeckB",
                exportDate = 200L
            ),
            ExportedWord(
                id = 3,
                expression = "飲む",
                reading = "のむ",
                deckName = "DeckA",
                exportDate = 150L
            )
        )

        val weekly = StatisticsViewModel.toWeeklyLearnedWords(exports)

        assertEquals(2, weekly.size)
        assertEquals("食べる", weekly[0].expression)
        assertEquals("飲む", weekly[1].expression)
    }

    @Test
    fun buildWeeklyLearnedWordsCopyText_formatsExpectedList() {
        val words = listOf(
            WeeklyLearnedWord(expression = "食べる", reading = "たべる", exportDate = 200L),
            WeeklyLearnedWord(expression = "犬", reading = "いぬ", exportDate = 150L)
        )

        val text = StatisticsViewModel.buildWeeklyLearnedWordsCopyText(words)

        assertTrue(text.startsWith("Słowa z ostatnich 7 dni (2)"))
        assertTrue(text.contains("1. 食べる (たべる)"))
        assertTrue(text.contains("2. 犬 (いぬ)"))
    }

    @Test
    fun buildWeeklyLearnedWordsCopyText_returnsEmptyForNoWords() {
        val text = StatisticsViewModel.buildWeeklyLearnedWordsCopyText(emptyList())

        assertEquals("", text)
    }

    @Test
    fun toHourlyActivity_sortsAscendingByHour() {
        val activity = StatisticsViewModel.toHourlyActivity(
            listOf(
                HourlyActivityCount(hour = 22, count = 3),
                HourlyActivityCount(hour = 8, count = 5),
                HourlyActivityCount(hour = 15, count = 2)
            )
        )

        assertEquals(listOf(8, 15, 22), activity.map { it.hour })
    }

    @Test
    fun findMostActiveHour_returnsEarliestHourOnTie() {
        val mostActive = StatisticsViewModel.findMostActiveHour(
            listOf(
                HourlyActivity(hour = 21, count = 4),
                HourlyActivity(hour = 10, count = 4),
                HourlyActivity(hour = 8, count = 1)
            )
        )

        assertEquals(10, mostActive?.hour)
        assertEquals(4, mostActive?.count)
    }

    @Test
    fun findMostActiveHour_returnsNullWhenNoPositiveCounts() {
        val mostActive = StatisticsViewModel.findMostActiveHour(
            listOf(
                HourlyActivity(hour = 9, count = 0),
                HourlyActivity(hour = 11, count = 0)
            )
        )

        assertNull(mostActive)
    }

    @Test
    fun hourRangeLabel_formatsLeadingZeros() {
        assertEquals("03:00-03:59", StatisticsViewModel.hourRangeLabel(3))
        assertEquals("23:00-23:59", StatisticsViewModel.hourRangeLabel(23))
    }
}
