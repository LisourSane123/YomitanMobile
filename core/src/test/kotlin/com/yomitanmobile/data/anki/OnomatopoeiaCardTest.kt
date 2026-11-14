package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.usecase.WordFilterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnomatopoeiaCardTest {

    @Test
    fun `the form the text uses is underlined, not the particle after it`() {
        assertEquals(
            "胸が<u class=\"onomatopoeia-word\">ドキドキ</u>と高鳴った。",
            OnomatopoeiaCard.underline("胸が ドキドキと高鳴った。".replace(" ", ""), listOf("どきどき"))
        )
        assertEquals(
            "<u class=\"onomatopoeia-word\">ぴくぴく</u>している。",
            OnomatopoeiaCard.underline("ぴくぴくしている。", listOf("ぴくぴく"))
        )
    }

    @Test
    fun `a sentence without the word gives no card`() {
        assertNull(OnomatopoeiaCard.underline("第一章", listOf("どきどき")))
        assertNull(OnomatopoeiaCard.underline("", listOf("どきどき")))
    }

    @Test
    fun `the sentence is escaped`() {
        assertEquals(
            "&lt;b&gt;<u class=\"onomatopoeia-word\">ざあざあ</u>",
            OnomatopoeiaCard.underline("<b>ざあざあ", listOf("ざあざあ"))
        )
    }

    @Test
    fun `fields come from the vocabulary card plus the sentence and book`() {
        val fields = OnomatopoeiaCard.fields(
            mapOf("Reading" to "どきどき", "Meaning" to "M", "PitchAccent" to "P", "Audio" to "[sound:a.wav]", "Frequency" to "4821"),
            "どきどき", "S", "本"
        )
        assertEquals(OnomatopoeiaCard.FIELD_NAMES, fields.keys.toList())
        assertEquals("", fields["ContextTranslation"])
        assertEquals("M", fields["Definition"])
        assertEquals("4821", fields["Frequency"])
    }

    @Test
    fun `on-mim makes an onomatopoeia, as a tag or as a chip`() {
        fun entry(pos: List<String> = emptyList(), usage: List<String> = emptyList()) = MergedWordEntry(
            primaryId = 1, primaryExpression = "どきどき", reading = "どきどき", definitions = listOf("x"),
            alternativeExpressions = emptyList(), partsOfSpeech = pos, usageTags = usage
        )
        assertTrue(WordFilterRules.isOnomatopoeia(entry(pos = listOf("adv, on-mim, vs"))))
        assertTrue(WordFilterRules.isOnomatopoeia(entry(usage = listOf("onomat."))))
        assertFalse(WordFilterRules.isOnomatopoeia(entry(pos = listOf("n, vs"))))
    }

    @Test
    fun `the name keeps the refresher's hands off`() {
        assertFalse(OnomatopoeiaCard.MODEL_NAME.startsWith("Yomitan-Mobile-v8"))
    }
}
