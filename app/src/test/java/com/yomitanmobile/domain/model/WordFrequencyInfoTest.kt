package com.yomitanmobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WordFrequencyInfoTest {

    private fun freq(dict: String, rank: Int, display: String = rank.toString()) =
        WordFrequencyInfo(dict, rank, display)

    @Test
    fun ordersByUserPriority() {
        val entries = listOf(freq("BCCWJ", 980), freq("JPDBv2", 1203))
        val ordered = WordFrequencyInfo.order(entries, listOf("JPDBv2", "BCCWJ"), showAll = true)
        assertEquals(listOf("JPDBv2", "BCCWJ"), ordered.map { it.dictionary })
    }

    @Test
    fun listsNotInPriorityKeepRelativeOrderAtEnd() {
        val entries = listOf(freq("Narou", 50), freq("JPDBv2", 1203), freq("BCCWJ", 980))
        val ordered = WordFrequencyInfo.order(entries, listOf("JPDBv2"), showAll = true)
        // JPDBv2 first (only one in priority); the rest keep their input order.
        assertEquals(listOf("JPDBv2", "Narou", "BCCWJ"), ordered.map { it.dictionary })
    }

    @Test
    fun showAllOffCollapsesToTopPriority() {
        val entries = listOf(freq("BCCWJ", 980), freq("JPDBv2", 1203))
        val ordered = WordFrequencyInfo.order(entries, listOf("JPDBv2", "BCCWJ"), showAll = false)
        assertEquals(listOf("JPDBv2"), ordered.map { it.dictionary })
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<WordFrequencyInfo>(), WordFrequencyInfo.order(emptyList(), listOf("JPDBv2"), true))
    }

    @Test
    fun labelPrefixesNumericRankWithHash() {
        assertEquals("JPDBv2 #1203", freq("JPDBv2", 1203).label())
    }

    @Test
    fun labelLeavesNonNumericDisplayValueAsIs() {
        assertEquals("Custom Top 10k", WordFrequencyInfo("Custom", 9000, "Top 10k").label())
    }

    @Test
    fun labelFallsBackToRankWhenDisplayBlank() {
        assertEquals("BCCWJ #980", WordFrequencyInfo("BCCWJ", 980, "").label())
    }
}
