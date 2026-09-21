package com.yomitanmobile.data.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Anki search the live duplicate check runs before a card is written.
 * Anki matches raw field text, so what matters is that every form a deck may
 * hold the word in contains what is searched for — above all ruby
 * (持[も]って 来[く]る), where the plain spelling is not a substring.
 */
class LiveDuplicateSearchTest {

    @Test
    fun `a kanji spelling is searched by its kanji, all required`() {
        val search = AnkiCollectionIndex.liveSearch(listOf("持って来る"), "")
        assertEquals("(\"持\" \"来\")", search)
    }

    @Test
    fun `every form a ruby field can take contains the searched kanji`() {
        val search = AnkiCollectionIndex.liveSearch(listOf("持って来る"), "もってくる")!!
        for (field in listOf("持って来る", "持[も]って 来[く]る", "<b>持って来る</b>")) {
            assertTrue(field, "持" in field && "来" in field)
        }
        assertTrue(search.contains("\"もってくる\""))
    }

    @Test
    fun `kana spellings and the reading are searched as themselves`() {
        assertEquals("\"しがみつく\"", AnkiCollectionIndex.liveSearch(listOf("しがみつく"), "しがみつく"))
    }

    @Test
    fun `each written form and the reading become one alternative`() {
        val search = AnkiCollectionIndex.liveSearch(listOf("しがみ付く", "しがみつく"), "しがみつく")
        assertEquals("(\"付\") OR \"しがみつく\"", search)
    }

    @Test
    fun `nothing to search for gives no search`() {
        assertNull(AnkiCollectionIndex.liveSearch(emptyList(), ""))
        assertNull(AnkiCollectionIndex.liveSearch(listOf("  "), " "))
    }

    @Test
    fun `wildcards and quotes cannot widen the search`() {
        val search = AnkiCollectionIndex.liveSearch(listOf("ア*ル\"_"), "")!!
        assertEquals("\"ア\\*ル\\\"\\_\"", search)
    }
}
