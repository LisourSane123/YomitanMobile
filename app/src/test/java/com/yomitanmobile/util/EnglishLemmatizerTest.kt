package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLemmatizerTest {

    private fun assertOffers(word: String, expected: String) {
        val candidates = EnglishLemmatizer.analyze(word)
        assertTrue(
            "expected '$expected' among candidates for '$word', got $candidates",
            candidates.contains(expected)
        )
    }

    @Test
    fun `plural s and es reach the singular`() {
        assertOffers("cats", "cat")
        assertOffers("boxes", "box")
        assertOffers("watches", "watch")
    }

    @Test
    fun `y plurals restore the y`() {
        assertOffers("studies", "study")
        assertOffers("cities", "city")
    }

    @Test
    fun `past tense reaches the base form`() {
        assertOffers("walked", "walk")
        assertOffers("tried", "try")
        // The silent -e the suffix displaced.
        assertOffers("liked", "like")
    }

    @Test
    fun `progressive reaches the base form`() {
        assertOffers("walking", "walk")
        assertOffers("making", "make")
        assertOffers("running", "run")
    }

    @Test
    fun `comparatives reach the adjective`() {
        assertOffers("bigger", "big")
        assertOffers("larger", "large")
        assertOffers("happiest", "happy")
    }

    @Test
    fun `the undoubled stem never outranks the plain one`() {
        // "running" → "run" and "falling" → "fall" are the same shape, and
        // nothing short of a lexicon tells them apart: both stems end in a
        // doubled consonant after a single vowel. So both readings are
        // offered and the plain stem comes first — the dictionary decides,
        // and the bogus one ("fal") simply matches nothing.
        val candidates = EnglishLemmatizer.analyze("falling")
        assertTrue(candidates.contains("fall"))
        assertTrue(candidates.indexOf("fall") < candidates.indexOf("fal"))
    }

    @Test
    fun `the word itself is never a candidate`() {
        assertFalse(EnglishLemmatizer.analyze("running").contains("running"))
    }

    @Test
    fun `short and non-alphabetic input yields nothing`() {
        assertTrue(EnglishLemmatizer.analyze("go").isEmpty())
        assertTrue(EnglishLemmatizer.analyze("12s").isEmpty())
        assertTrue(EnglishLemmatizer.analyze("").isEmpty())
    }
}
