package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun `irregular plurals the dictionary has no headword for reach their noun`() {
        assertEquals("child", EnglishLemmatizer.analyze("children").first())
        assertEquals("woman", EnglishLemmatizer.analyze("women").first())
        assertEquals("tooth", EnglishLemmatizer.analyze("teeth").first())
        assertEquals("mouse", EnglishLemmatizer.analyze("mice").first())
        assertEquals("criterion", EnglishLemmatizer.analyze("criteria").first())
        assertEquals("crisis", EnglishLemmatizer.analyze("crises").first())
        assertEquals("cactus", EnglishLemmatizer.analyze("cacti").first())
    }

    @Test
    fun `irregular verbs and comparatives offer their base first`() {
        assertEquals("go", EnglishLemmatizer.analyze("went").first())
        assertEquals("write", EnglishLemmatizer.analyze("written").first())
        assertEquals(listOf("good", "well"), EnglishLemmatizer.analyze("better").take(2))
        assertEquals(listOf("be"), EnglishLemmatizer.analyze("been").take(1))
    }

    @Test
    fun `a form that is two words' form offers both`() {
        // leaves: the plural of leaf, and "leave" + s.
        val leaves = EnglishLemmatizer.analyze("leaves")
        assertTrue(leaves.toString(), "leaf" in leaves && "leave" in leaves)
        val bases = EnglishLemmatizer.analyze("bases")
        assertTrue(bases.toString(), "basis" in bases && "base" in bases)
    }

    @Test
    fun `no suffix rule offers a real word that is the wrong one`() {
        // The rules a table replaced would have said belief, safe, indium.
        assertTrue("belief" !in EnglishLemmatizer.analyze("believes"))
        assertTrue("safe" !in EnglishLemmatizer.analyze("saves"))
        assertTrue("indium" !in EnglishLemmatizer.analyze("india"))
    }

    @Test
    fun `the table parses verbs and nouns into form to base`() {
        val parsed = EnglishLemmatizer.parseIrregular(
            "# nouns\nmen man\naxes axis,axe\n# verbs: base past participle\nget got got,gotten\n"
        )
        assertEquals(listOf("man"), parsed["men"])
        assertEquals(listOf("axis", "axe"), parsed["axes"])
        assertEquals(listOf("get"), parsed["gotten"])
        assertTrue("get" !in parsed)
    }
}
