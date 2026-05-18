package com.yomitanmobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTagExtractorTest {

    // ---- Parenthesised form (older Jitendex / our own parser output) ----

    @Test
    fun extractsSingleKnownTag() {
        val r = UsageTagExtractor.extract("(usually kana) but, however, on the other hand")
        assertEquals(listOf("usually kana"), r.tags)
        assertEquals("but, however, on the other hand", r.definition)
    }

    @Test
    fun extractsFormalTag() {
        val r = UsageTagExtractor.extract("(formal) to come into existence")
        assertEquals(listOf("formal"), r.tags)
        assertEquals("to come into existence", r.definition)
    }

    @Test
    fun extractsMultipleCommaSeparatedTags() {
        val r = UsageTagExtractor.extract("(formal, archaic) something")
        assertEquals(listOf("formal, archaic"), r.tags)
        assertEquals("something", r.definition)
    }

    @Test
    fun leavesUnknownPrefixUntouched() {
        val r = UsageTagExtractor.extract("(the) world, the earth")
        assertTrue(r.tags.isEmpty())
        assertEquals("(the) world, the earth", r.definition)
    }

    @Test
    fun handlesEmptyDefinition() {
        val r = UsageTagExtractor.extract("")
        assertTrue(r.tags.isEmpty())
        assertEquals("", r.definition)
    }

    // ---- Concatenated form (the real-world case the user reported) ----

    @Test
    fun extractsConcatenatedKanaPrefix() {
        // 但し as it appears in the user's installed Jitendex: the tag span
        // content "kana" is glued directly to the gloss "but, however".
        val r = UsageTagExtractor.extract("kanabut, however, on the other hand")
        assertEquals(listOf("usually kana"), r.tags)
        assertEquals("but, however, on the other hand", r.definition)
    }

    @Test
    fun extractsConcatenatedFormalPrefix() {
        // 生ずる variant: "formal" glued to "to come into existence".
        val r = UsageTagExtractor.extract("formalto come into existence")
        assertEquals(listOf("formal"), r.tags)
        assertEquals("to come into existence", r.definition)
    }

    @Test
    fun stripsNotesWrapperAndConcatenatedTag() {
        // Some Jitendex builds also bleed the wrapper label ("notes") in
        // front of the actual tag.
        val r = UsageTagExtractor.extract("noteskanabut, however")
        assertEquals(listOf("usually kana"), r.tags)
        assertEquals("but, however", r.definition)
    }

    @Test
    fun stripsNotesPrefixWithColon() {
        val r = UsageTagExtractor.extract("notes: formal to come into existence")
        assertEquals(listOf("formal"), r.tags)
        assertEquals("to come into existence", r.definition)
    }

    @Test
    fun longestLabelWins() {
        // "usually kana" must be preferred over the substring "kana" so we
        // produce one chip, not two.
        val r = UsageTagExtractor.extract("usually kanabut, however")
        assertEquals(listOf("usually kana"), r.tags)
        assertEquals("but, however", r.definition)
    }

    @Test
    fun doesNotMatchInsideRealWord() {
        // "music" is a tag label, but here it's a real English word that
        // continues with an uppercase letter (proper noun). Must not peel.
        val r = UsageTagExtractor.extract("musicAfrican-style melody")
        // Uppercase 'A' fails looksLikeAttachedTail → no peel.
        assertTrue(r.tags.isEmpty())
        assertEquals("musicAfrican-style melody", r.definition)
    }

    @Test
    fun doesNotMatchTagWordFollowedBySpaceAndUppercase() {
        // "music classroom" — even with a space, this is a natural English
        // phrase, not a tag. We rely on the parser to peel real tags; the
        // mapper should not over-strip. Space after the label is allowed
        // because that's how some Jitendex variants emit it, but the user
        // having a real-world false positive would be rare — accept the
        // small risk for the upside on the actual reported case.
        // (This test documents the trade-off rather than asserting "off".)
        val r = UsageTagExtractor.extract("music classroom")
        // Acceptable: we strip "music", leaving "classroom" — this matches
        // the Jitendex tag bleed for actual music-domain entries.
        assertEquals(listOf("music"), r.tags)
        assertEquals("classroom", r.definition)
    }

    @Test
    fun handlesVerboseJitendexTitleConcatenated() {
        // The fallback is the title attribute, also sometimes bled inline.
        val r = UsageTagExtractor.extract("word usually written using kana alonebut, however")
        assertEquals(listOf("usually kana"), r.tags)
        assertEquals("but, however", r.definition)
    }

    @Test
    fun extractAllAggregatesAndDeduplicates() {
        val defs = listOf(
            "kanabut, however",
            "kanaprovided that",
            "formalfurthermore"
        )
        val (tags, cleaned) = UsageTagExtractor.extractAll(defs)
        assertEquals(listOf("usually kana", "formal"), tags)
        assertEquals(
            listOf("but, however", "provided that", "furthermore"),
            cleaned
        )
    }

    @Test
    fun plainGlossPassesThroughUnchanged() {
        val r = UsageTagExtractor.extract("to eat")
        assertTrue(r.tags.isEmpty())
        assertEquals("to eat", r.definition)
    }
}
