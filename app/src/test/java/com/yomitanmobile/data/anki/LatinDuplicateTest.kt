package com.yomitanmobile.data.anki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English words in the duplicate check: indexed from a note's FIRST field
 * only, so an English deck's word counts and a Japanese card's gloss does not.
 */
class LatinDuplicateTest {

    private val sep = "\u001f"

    @Test
    fun `an English note's word is indexed from its first field`() {
        val keys = AnkiNoteFieldIndexer.keysFromNote("<b>Dog</b>${sep}pies${sep}[sound:dog.wav]")
        assertTrue(keys.toString(), "dog" in keys)
    }

    @Test
    fun `a Japanese card's English gloss is never an English word in the collection`() {
        val keys = AnkiNoteFieldIndexer.keysFromNote("犬${sep}いぬ${sep}dog")
        assertTrue("犬" in keys)
        assertFalse(keys.toString(), "dog" in keys)
    }

    @Test
    fun `a multi-word headword counts, a sentence does not`() {
        assertTrue("icecream" in AnkiNoteFieldIndexer.keysFromNote("Ice cream${sep}lody"))
        assertTrue(AnkiNoteFieldIndexer.keysFromNote("The dog ran across the busy road today${sep}x").none { it.contains("dog") })
    }

    @Test
    fun `the lookup finds an English word in any casing, and not another word`() {
        val index = AnkiCollectionIndex.Index(setOf("dog", "icecream", "犬"), 3, available = true)
        assertTrue(index.containsAny(listOf("Dog"), "Dog"))
        assertTrue(index.containsAny(listOf("ice cream"), "ice cream"))
        assertFalse(index.containsAny(listOf("cat"), "cat"))
        // Japanese is untouched by it.
        assertTrue(index.containsAny(listOf("犬"), "いぬ"))
    }

    @Test
    fun `Spanish letters are Latin too`() {
        assertTrue("niño" in AnkiNoteFieldIndexer.keysFromNote("niño${sep}child"))
    }

    @Test
    fun `an inflected form is found through the word the collection holds`() {
        val index = AnkiCollectionIndex.Index(setOf("make", "child", "study", "moth"), 4, available = true)
        assertTrue(index.containsAny(listOf("made"), "made"))
        assertTrue(index.containsAny(listOf("makes"), "makes"))
        assertTrue(index.containsAny(listOf("making"), "making"))
        assertTrue(index.containsAny(listOf("children"), "children"))
        assertTrue(index.containsAny(listOf("studies"), "studies"))
        // The -er rule answers "moth" for "mother": a card for mother must
        // still be made by a collection that happens to hold moth.
        assertFalse(index.containsAny(listOf("mother"), "mother"))
        assertFalse(index.containsAny(listOf("makeshift"), "makeshift"))
    }

    @Test
    fun `the live search asks Anki about the base form too`() {
        val search = AnkiCollectionIndex.liveSearch(listOf("made"), "made").orEmpty()
        assertTrue(search, "\"make\"" in search)
        assertTrue(search, "\"made\"" in search)
    }
}
