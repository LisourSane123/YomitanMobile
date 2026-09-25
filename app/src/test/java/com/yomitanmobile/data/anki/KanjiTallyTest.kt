package com.yomitanmobile.data.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiTallyTest {

    private fun counts(vararg words: String) =
        KanjiTally.of(words.toList()).counts.associate { it.kanji to it.words }

    @Test
    fun `counts the words each character appears in`() {
        val tally = KanjiTally.of(listOf("食べる", "食事", "朝食", "人", "人間"))
        val byKanji = tally.counts.associateBy { it.kanji }
        assertEquals(3, byKanji["食"]?.words)
        assertEquals(2, byKanji["人"]?.words)
        assertEquals(1, byKanji["間"]?.words)
        // Commonest first, and a tie is broken by the character so two runs agree.
        assertEquals(listOf("食", "人", "事", "朝", "間"), tally.counts.map { it.kanji })
    }

    @Test
    fun `a character written twice in one word is one word and two occurrences`() {
        val tally = KanjiTally.of(listOf("日曜日"))
        val day = tally.counts.single { it.kanji == "日" }
        assertEquals(1, day.words)
        assertEquals(2, day.occurrences)
        assertEquals(3, tally.totalOccurrences)
        assertEquals(2, tally.distinctKanji)
    }

    @Test
    fun `kana-only words carry no kanji and are not counted as if they did`() {
        val tally = KanjiTally.of(listOf("たべる", "ひらがな", "食べる"))
        assertEquals(3, tally.wordCount)
        assertEquals(1, tally.wordsWithKanji)
        assertEquals(1, tally.distinctKanji)
    }

    @Test
    fun `the repeat mark and the counter katakana are not kanji to study`() {
        // AnkiNoteFieldIndexer counts these as part of a spelling, on purpose;
        // a tally of characters worth studying must not.
        assertTrue(AnkiNoteFieldIndexer.isKanji('々'))
        assertFalse(KanjiTally.isKanji('々'))
        assertFalse(KanjiTally.isKanji('ヶ'))
        assertFalse(KanjiTally.isKanji('ル'))
        assertTrue(KanjiTally.isKanji('食'))

        val tally = KanjiTally.of(listOf("人々", "一ヶ月"))
        assertEquals(setOf("人", "一", "月"), tally.counts.mapTo(HashSet()) { it.kanji })
        assertEquals(1, tally.counts.single { it.kanji == "人" }.occurrences)
    }

    @Test
    fun `an empty scan tallies to nothing rather than failing`() {
        val tally = KanjiTally.of(emptyList())
        assertEquals(0, tally.distinctKanji)
        assertEquals(0, tally.totalOccurrences)
        assertEquals(0, tally.wordCount)
    }

    @Test
    fun `a whole card's front is read, not just its first character`() {
        assertEquals(mapOf("持" to 1, "来" to 1), counts("持って来る"))
    }
}
