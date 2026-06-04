package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesExtractorTest {

    @Test
    fun `parser-marked extra-info note is routed to notes`() {
        val input = listOf(
            "to eat",
            "${NotesExtractor.NOTE_MARKER}usually written in kana"
        )
        val result = NotesExtractor.extractAll(input)
        assertEquals(listOf("to eat"), result.definitions)
        assertEquals(listOf("usually written in kana"), result.notes)
    }

    @Test
    fun `multiple marked notes are kept in first-seen order`() {
        val input = listOf(
            "${NotesExtractor.NOTE_MARKER}see 召し上がる",
            "to eat",
            "${NotesExtractor.NOTE_MARKER}usually written in kana"
        )
        val result = NotesExtractor.extractAll(input)
        assertEquals(listOf("to eat"), result.definitions)
        assertEquals(
            listOf("see 召し上がる", "usually written in kana"),
            result.notes
        )
    }

    @Test
    fun `whole-definition cross-reference moves to notes`() {
        val input = listOf("to eat", "see also 食べる")
        val result = NotesExtractor.extractAll(input)
        assertEquals(listOf("to eat"), result.definitions)
        assertEquals(listOf("see also 食べる"), result.notes)
    }

    @Test
    fun `trailing reference is peeled off and gloss kept`() {
        val input = listOf("to eat (see also 食べる)")
        val result = NotesExtractor.extractAll(input)
        assertEquals(listOf("to eat"), result.definitions)
        assertEquals(listOf("see also 食べる"), result.notes)
    }

    @Test
    fun `note prefix is recognized`() {
        val input = listOf("Note: usually written in kana, not kanji")
        val result = NotesExtractor.extractAll(input)
        assertTrue(
            "expected definitions to be empty, was ${result.definitions}",
            result.definitions.isEmpty()
        )
        assertEquals(
            listOf("Note: usually written in kana, not kanji"),
            result.notes
        )
    }

    @Test
    fun `plain gloss passes through untouched`() {
        val input = listOf("to eat", "to consume", "to bite")
        val result = NotesExtractor.extractAll(input)
        assertEquals(input, result.definitions)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `marker preserved exactly across parser-extractor boundary`() {
        // Guard against drift: if the parser ever stamps a slightly different
        // marker, the harvested note text would silently fall back to being
        // treated as a regular gloss. Bumping this assertion forces both
        // sides to update together.
        assertEquals("⟦note⟧ ", NotesExtractor.NOTE_MARKER)
    }
}
