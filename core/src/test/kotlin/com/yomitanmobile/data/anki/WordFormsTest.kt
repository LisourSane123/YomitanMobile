package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Some words are not detected and I have duplicate flashcards, especially
 * from Yomitan or Core 2k."
 *
 * Every case here is a pair from one real collection: a mined card beside a
 * Core 2.3k / desktop Yomitan / Kanji Study note for the same word, which the
 * headword rule could not see — or, just as important, a pair it must keep
 * apart, because a false "you already have it" drops a word from every deck
 * for good.
 */
class WordFormsTest {

    private fun row(expression: String, reading: String, seq: Int, rank: Int = 0, uk: Boolean = false) =
        WordEntry(
            expression = expression,
            reading = reading,
            definitions = listOf("gloss"),
            frequency = rank,
            sequenceNumber = seq,
            usageTags = if (uk) listOf("usually kana") else emptyList()
        )

    private fun entry(expression: String, reading: String, rows: List<WordEntry>): MergedWordEntry =
        MergedWordEntry.mergeEntries(rows.filter { it.expression == expression && it.reading == reading }).single()

    /** Notes as AnkiNoteFieldIndexer reads them: fields joined by U+001F. */
    private fun collection(vararg notes: String): AnkiCollectionMatch.Index {
        val facts = AnkiCollectionMatch.NoteFacts()
        for (note in notes) facts.add(AnkiNoteFieldIndexer.keysFromNote(note))
        return facts.build(notes.size)
    }

    private fun AnkiCollectionMatch.Index.has(expression: String, reading: String, rows: List<WordEntry>): Boolean {
        val e = entry(expression, reading, rows)
        return containsAny(listOf(e.primaryExpression), e.reading, forms = WordForms.Lookup(rows).of(e))
    }

    // Core 2.3k's note shape: Word, Reading, Glossary, Sentence.
    private fun core(word: String, reading: String, gloss: String) =
        "$word\u001f$reading\u001f$gloss\u001fこれは例文です。とても長い文になっています。"

    @Test
    fun `another spelling of the entry counts when the note holds the reading too`() {
        val rows = listOf(
            row("身体", "からだ", 1409140, rank = 3000),
            row("体", "からだ", 1409140, rank = 300)
        )
        assertTrue(collection(core("体", "からだ", "body, health")).has("身体", "からだ", rows))
    }

    @Test
    fun `a rare spelling that is another word's headword does not`() {
        // JMdict files 風 as a spelling of 振り (ふり); Core's 風 is かぜ, wind.
        val rows = listOf(
            row("振り", "ふり", 1012980, rank = 2000),
            row("風", "ふり", 1012980)
        )
        assertFalse(collection(core("風", "かぜ", "wind")).has("振り", "ふり", rows))
    }

    @Test
    fun `a deck that files the word in kana is the word when nothing else is read that way`() {
        val rows = listOf(row("膝", "ひざ", 1487320, rank = 1538))
        assertTrue(collection(core("ひざ", "ひざ", "knee")).has("膝", "ひざ", rows))
    }

    @Test
    fun `a homophone no list ranks does not stand in the way`() {
        val rows = listOf(
            row("呆れる", "あきれる", 1586800, rank = 3042),
            // No list ranks it and it is not a kana word: nobody's deck means it.
            row("惘れる", "あきれる", 9999999, rank = 0)
        )
        assertTrue(collection(core("あきれる", "あきれる", "be stunned")).has("呆れる", "あきれる", rows))
    }

    @Test
    fun `a common homophone keeps the kana note from answering`() {
        // Core's くれる is 呉れる "to give"; 暮れる "to get dark" is another card.
        val rows = listOf(
            row("暮れる", "くれる", 1260900, rank = 3000),
            row("呉れる", "くれる", 1269130, rank = 40, uk = true)
        )
        assertFalse(collection(core("くれる", "くれる", "be given")).has("暮れる", "くれる", rows))
    }

    @Test
    fun `a homophone any list ranks keeps it too, however rare`() {
        // ついで in a deck is 序で "on the way", not 次いで "next". 序で is
        // written in kana, so its kanji spelling ranks 168 990 — and its
        // "usually kana" is not in the parsed tags, so the rank is all there is.
        val rows = listOf(
            row("次いで", "ついで", 1316390, rank = 10008),
            row("序で", "ついで", 1345470, rank = 168990)
        )
        assertFalse(collection(core("ついで", "ついで", "on the way")).has("次いで", "ついで", rows))
    }

    @Test
    fun `an unranked usually-kana homophone keeps it as well`() {
        val rows = listOf(
            row("零れる", "こぼれる", 1, rank = 8578),
            row("毀れる", "こぼれる", 2, rank = 0, uk = true)
        )
        assertFalse(collection(core("こぼれる", "こぼれる", "spill")).has("零れる", "こぼれる", rows))
    }

    @Test
    fun `the reading field of a kanji note never stands for a homophone`() {
        // The deck has 意志 (いし). Mining 医師 must not be blocked by its reading,
        // even if no list ranked either word.
        val rows = listOf(
            row("医師", "いし", 1, rank = 4086),
            row("意志", "いし", 2, rank = 0)
        )
        assertFalse(collection(core("意志", "いし", "will")).has("医師", "いし", rows))
    }

    @Test
    fun `without frequency lists a kana note only answers when no homophone exists at all`() {
        val homophones = listOf(row("医師", "いし", 1), row("石", "いし", 2))
        assertFalse(collection(core("いし", "いし", "stone")).has("医師", "いし", homophones))
        val alone = listOf(row("膝", "ひざ", 1487320))
        assertTrue(collection(core("ひざ", "ひざ", "knee")).has("膝", "ひざ", alone))
    }

    @Test
    fun `an entry with no sequence keeps the headword rule alone`() {
        val rows = listOf(row("手作り", "てづくり", 0), row("手造り", "てづくり", 0))
        assertFalse(collection(core("手造り", "てづくり", "handmade")).has("手作り", "てづくり", rows))
    }

    @Test
    fun `an index rebuilt from stored words answers by the headword rule as before`() {
        val rows = listOf(row("身体", "からだ", 1409140), row("体", "からだ", 1409140))
        val stored = AnkiCollectionMatch.Index(setOf("体", "からだ"), 0, available = true)
        val e = entry("身体", "からだ", rows)
        assertFalse(stored.containsAny(listOf("身体"), "からだ", forms = WordForms.Lookup(rows).of(e)))
        assertTrue(stored.containsAny(listOf("体"), "からだ"))
    }
}
