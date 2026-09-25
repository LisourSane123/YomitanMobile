package com.yomitanmobile.data.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiStudyExportTest {

    private fun count(kanji: String, words: Int) =
        KanjiTally.KanjiCount(kanji, words = words, occurrences = words)

    @Test
    fun `most of my words first, media frequency breaks the ties`() {
        val counts = listOf(
            count("食", 5),
            count("人", 9),
            // Three characters with the same count: the media rank orders them.
            count("鬱", 3), count("日", 3), count("森", 3)
        )
        val ranks = mapOf("人" to 5, "食" to 200, "日" to 1, "森" to 400)
        val plan = KanjiStudyExport.plan(counts, mediaRank = { ranks[it] ?: 0 })
        // 鬱 is unranked, so it goes last among the threes rather than first.
        assertEquals(listOf("人", "食", "日", "森", "鬱"), plan.kanji)
        assertEquals(1, plan.unranked)
    }

    @Test
    fun `a character in a single word is left out`() {
        val counts = listOf(count("人", 4), count("食", 2), count("鬱", 1), count("薔", 1))
        val plan = KanjiStudyExport.plan(counts)
        assertEquals(listOf("人", "食"), plan.kanji)
        assertEquals(2, plan.dropped)
    }

    @Test
    fun `one line per set, characters run together, trailing newline`() {
        val counts = (1..45).map { count(('一' + it).toString(), words = 50 - it) }
        val plan = KanjiStudyExport.plan(counts, setSize = 20)
        assertEquals(3, plan.sets.size)
        assertEquals(20, plan.sets[0].length)
        assertEquals(5, plan.sets[2].length)
        val text = KanjiStudyExport.text(plan)
        assertEquals(3, text.trim().lines().size)
        assertTrue(text.endsWith("\n"))
        // No separators inside a line: that is the format the app reads.
        assertEquals(plan.kanji.take(20).joinToString(""), text.lines().first())
    }

    @Test
    fun `an empty collection exports nothing rather than an empty line`() {
        val plan = KanjiStudyExport.plan(emptyList())
        assertTrue(plan.isEmpty)
        assertEquals("", KanjiStudyExport.text(plan))
    }

    @Test
    fun `the defaults are the ones that were reasoned about`() {
        assertEquals(2, KanjiStudyExport.DEFAULT_MIN_WORDS)
        assertEquals(20, KanjiStudyExport.DEFAULT_SET_SIZE)
        val plan = KanjiStudyExport.plan(listOf(count("人", 2), count("食", 1)))
        assertEquals(listOf("人"), plan.kanji)
    }
}
