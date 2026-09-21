package com.yomitanmobile.data.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshMergeTest {

    private fun note(vararg pairs: Pair<String, String>) = linkedMapOf(*pairs)

    @Test
    fun `the sentence a word was mined from survives a dictionary example`() {
        val merged = RefreshMerge.merge(
            note("FrontContext" to "俺は三十四歳<strong>住所不定</strong>無職。"),
            mapOf("FrontContext" to "住所不定の男。")
        )
        assertEquals("俺は三十四歳<strong>住所不定</strong>無職。", merged["FrontContext"])
    }

    @Test
    fun `the front keeps the spelling and font the card is known by`() {
        val front = "<span style=\"font-family: 'Klee One';\">えっち</span>"
        val merged = RefreshMerge.merge(
            note("Front" to front),
            mapOf("Front" to "<span style=\"font-family: 'M PLUS 1p';\">Ｈ</span>")
        )
        assertEquals(front, merged["Front"])
    }

    @Test
    fun `an empty field the note owns is filled`() {
        val merged = RefreshMerge.merge(
            note("Front" to "", "FrontContext" to "", "Summary" to ""),
            mapOf("Front" to "語", "FrontContext" to "例文。", "Summary" to "")
        )
        assertEquals("語", merged["Front"])
        assertEquals("例文。", merged["FrontContext"])
        assertEquals("", merged["Summary"])
    }

    @Test
    fun `an ai summary is never overwritten`() {
        val merged = RefreshMerge.merge(note("Summary" to "costly"), mapOf("Summary" to "other"))
        assertEquals("costly", merged["Summary"])
    }

    @Test
    fun `a tier label becomes the rank`() {
        val merged = RefreshMerge.merge(note("Frequency" to "★★★ Top 1K"), mapOf("Frequency" to "812"))
        assertEquals("812", merged["Frequency"])
    }

    @Test
    fun `a tier label is dropped even without a rank to replace it`() {
        val merged = RefreshMerge.merge(note("Frequency" to "★★ Top 5K"), mapOf("Frequency" to ""))
        assertEquals("", merged["Frequency"])
    }

    @Test
    fun `a rank is kept when the leading list does not know the word`() {
        val merged = RefreshMerge.merge(note("Frequency" to "4821"), mapOf("Frequency" to ""))
        assertEquals("4821", merged["Frequency"])
    }

    @Test
    fun `dictionary fields take the rebuild and fall back to the old value`() {
        val merged = RefreshMerge.merge(
            note("Meaning" to "old", "PitchAccent" to "old pitch", "KanjiBreakdown" to ""),
            mapOf("Meaning" to "new", "PitchAccent" to "", "KanjiBreakdown" to "kanji")
        )
        assertEquals("new", merged["Meaning"])
        assertEquals("old pitch", merged["PitchAccent"])
        assertEquals("kanji", merged["KanjiBreakdown"])
    }

    @Test
    fun `only the note type's own fields come back, in its order`() {
        val merged = RefreshMerge.merge(
            note("Reading" to "a", "Front" to "b"),
            mapOf("Front" to "x", "Reading" to "y", "Summary" to "z")
        )
        assertEquals(listOf("Reading", "Front"), merged.keys.toList())
    }

    @Test
    fun `a meaning the user annotated is kept whole`() {
        val annotated = """<div class="pos-line">noun</div><ol class="meanings"><li class="meaning-item">""" +
            """<span class="gloss">pupil (of the eye) źrenica</span></li></ol>"""
        val merged = RefreshMerge.merge(note("Meaning" to annotated), mapOf("Meaning" to "<span>pupil</span>"))
        assertEquals(annotated, merged["Meaning"])
    }

    @Test
    fun `the app's own polish labels are not the user's writing`() {
        val appOnly = """<div class="pos-line">przysłówek z と</div><div class="usage-tags">zwykle kaną · przysłowie</div>""" +
            """<ol class="meanings"><li class="meaning-item"><span class="gloss">timidly, nervously</span></li></ol>"""
        assertFalse(RefreshMerge.hasUserWriting(appOnly))
        val merged = RefreshMerge.merge(note("Meaning" to appOnly), mapOf("Meaning" to "new"))
        assertEquals("new", merged["Meaning"])
    }

    @Test
    fun `free text below a gloss is the user's`() {
        assertTrue(RefreshMerge.hasUserWriting("to fly about<br><br>latać na krzyż / w tę i z powrotem."))
        assertFalse(RefreshMerge.hasUserWriting("to fly about, to flutter about"))
        assertFalse(RefreshMerge.hasUserWriting(""))
    }

    @Test
    fun `a plain rank is digits only`() {
        assertTrue(RefreshMerge.isPlainRank(" 4821 "))
        assertFalse(RefreshMerge.isPlainRank("★★★ Top 1K"))
        assertFalse(RefreshMerge.isPlainRank(""))
        assertFalse(RefreshMerge.isPlainRank("4821 (JPDB)"))
    }
}
