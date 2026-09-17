package com.yomitanmobile.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The naming guesswork over a user's pronunciation archive.
 *
 * Every case here is a shape a real archive uses. The rule the tests are
 * really pinning is that a file is indexed under everything it could be asked
 * for, and that a spelling outranks a bare reading — several words share a
 * reading, and only one of them is the word on the card.
 */
class AudioKeysTest {

    private fun keys(fileName: String, parent: String = "") =
        AudioKeys.keysFor(fileName, parent).toMap()

    @Test
    fun `a bare expression is indexed as a spelling`() {
        val result = keys("食べる.mp3")
        assertEquals(mapOf("食べる" to AudioKeys.PRIORITY_EXPRESSION), result)
    }

    @Test
    fun `expression and reading in one name are indexed apart and together`() {
        val result = keys("食べる_たべる.mp3")
        assertEquals(AudioKeys.PRIORITY_PAIR, result["食べる\tたべる"])
        assertEquals(AudioKeys.PRIORITY_EXPRESSION, result["食べる"])
        assertEquals(AudioKeys.PRIORITY_READING, result["たべる"])
    }

    @Test
    fun `the pair is found whichever way round the archive wrote it`() {
        val written = keys("たべる - 食べる.mp3")
        assertEquals(AudioKeys.PRIORITY_PAIR, written["食べる\tたべる"])
    }

    @Test
    fun `a folder can hold the expression and the file the reading`() {
        val result = keys("たべる.mp3", parent = "食べる")
        assertEquals(AudioKeys.PRIORITY_PAIR, result["食べる\tたべる"])
        assertEquals(AudioKeys.PRIORITY_EXPRESSION, result["食べる"])
    }

    @Test
    fun `a file with nothing Japanese in its name is not indexed`() {
        assertTrue(AudioKeys.keysFor("track01.mp3", "Album").isEmpty())
    }

    @Test
    fun `a lookup asks for the pair before either half`() {
        val asked = AudioKeys.lookupKeys("食べる", "たべる")
        assertEquals("食べる\tたべる", asked.first())
        assertTrue("食べる" in asked)
        assertTrue("たべる" in asked)
    }

    @Test
    fun `a katakana headword still finds a file spelled in hiragana`() {
        // The archive wrote こーひー; the dictionary says コーヒー. Neither is
        // wrong, so the lookup asks for both scripts.
        val asked = AudioKeys.lookupKeys("コーヒー", "コーヒー")
        assertTrue("こーひー" in asked)
    }

    @Test
    fun `a reading-only word asks for one key, not a pair with itself`() {
        val asked = AudioKeys.lookupKeys("たべる", "たべる")
        assertTrue(asked.none { it.contains('\t') })
    }

    @Test
    fun `spaces in a name do not change the key`() {
        val result = keys("食べ る.mp3")
        assertTrue("食べる" in result)
    }
}
