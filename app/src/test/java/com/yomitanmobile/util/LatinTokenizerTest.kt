package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinTokenizerTest {

    /** A small English dictionary: the headwords the app would have installed. */
    private val dictionary = setOf(
        "child", "children", "run", "go", "study", "make", "house", "in-house",
        "do", "be", "is", "have", "book", "read", "cat", "half", "forgotten",
        "English", "Monday", "knife", "old", "man", "walk", "quick", "dog"
    )

    private fun scan(vararg text: String): Map<String, LatinTokenizer.Token> {
        val accumulator = LatinTokenizer.Accumulator(LatinTokenizer.ENGLISH)
        for (part in text) accumulator.add(part, LatinTokenizer.Lexicon { it in dictionary })
        return accumulator.tokens().associateBy { it.baseForm }
    }

    @Test
    fun `an inflected form reaches the headword and the counts add up`() {
        val tokens = scan("The children ran home. The child studies, and the children read books.")
        // children is a headword of its own here, so it is not reduced further.
        assertEquals(2, tokens["children"]?.count)
        assertEquals(1, tokens["child"]?.count)
        assertEquals(1, tokens["study"]?.count)
        assertEquals(1, tokens["book"]?.count)
        assertTrue(tokens["study"]!!.wasInflected)
    }

    @Test
    fun `a possessive and a contraction are cut where the dictionary confirms the stem`() {
        val tokens = scan("The cat's bowl. He doesn't run. The man's dog won't go.")
        assertEquals(1, tokens["cat"]?.count)
        assertEquals(1, tokens["man"]?.count)
        assertEquals(1, tokens["do"]?.count)
        assertEquals(1, tokens["run"]?.count)
        assertEquals(1, tokens["go"]?.count)
    }

    @Test
    fun `a hyphenated compound is kept whole when the dictionary lists it`() {
        val tokens = scan("An in-house team of half-forgotten men.")
        assertEquals(1, tokens["in-house"]?.count)
        // Not a headword, so it is read as its parts rather than lost.
        assertEquals(1, tokens["half"]?.count)
        assertEquals(1, tokens["forgotten"]?.count)
    }

    @Test
    fun `only a capital in the middle of a sentence counts as a name`() {
        val tokens = scan("Monday came. The dog met Monday. English is English.")
        // Three occurrences, two of them mid-sentence.
        assertEquals(2, tokens["Monday"]?.count)
        assertEquals(1, tokens["Monday"]?.nameHits)
        assertEquals(2, tokens["English"]?.count)
        assertEquals(1, tokens["English"]?.nameHits)
    }

    @Test
    fun `a word no dictionary knows is still counted, lowercased`() {
        val tokens = scan("Hogwarts is old. The dog left Hogwarts.")
        assertEquals(2, tokens["hogwarts"]?.count)
        assertEquals(1, tokens["hogwarts"]?.nameHits)
    }

    @Test
    fun `the sentence a word was met in is kept for the card front`() {
        val tokens = scan("Nothing here.\nThe quick old dog walks past the house today.")
        assertEquals("The quick old dog walks past the house today.", tokens["dog"]?.sentence)
    }

    @Test
    fun `a full stop inside an abbreviation or a number does not end the sentence`() {
        val sentences = mutableListOf<String>()
        LatinTokenizer.forEachSentence("Mr. Smith paid 3.50 today. He left.") { s, _ -> sentences += s.trim() }
        assertEquals(listOf("Mr. Smith paid 3.50 today.", "He left."), sentences)
    }

    @Test
    fun `a line break ends a sentence, which is what subtitles and lists need`() {
        val sentences = mutableListOf<String>()
        LatinTokenizer.forEachSentence("first line\nsecond line") { s, _ -> sentences += s.trim() }
        assertEquals(listOf("first line", "second line"), sentences)
    }

    @Test
    fun `the first offset is where the word was first met, across files`() {
        val accumulator = LatinTokenizer.Accumulator(LatinTokenizer.ENGLISH)
        val lexicon = LatinTokenizer.Lexicon { it in dictionary }
        accumulator.add("the house", lexicon)
        accumulator.add(" the cat", lexicon)
        val tokens = accumulator.tokens().associateBy { it.baseForm }
        assertEquals(4, tokens["house"]?.firstOffset)
        assertEquals(14, tokens["cat"]?.firstOffset)
        assertEquals(17, accumulator.totalLength)
    }

    @Test
    fun `spanish text tokenises without a lemmatiser`() {
        val spanish = setOf("casa", "perro", "grande")
        val accumulator = LatinTokenizer.Accumulator(LatinTokenizer.NONE)
        accumulator.add("La casa grande. El perro corrió.", LatinTokenizer.Lexicon { it in spanish })
        val tokens = accumulator.tokens().associateBy { it.baseForm }
        assertEquals(1, tokens["casa"]?.count)
        assertEquals(1, tokens["perro"]?.count)
        // No Spanish lemmatiser: the conjugated verb stays as written.
        assertEquals(1, tokens["corrió"]?.count)
    }
}
