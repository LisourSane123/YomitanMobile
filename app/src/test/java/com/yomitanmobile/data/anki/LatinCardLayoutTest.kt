package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardProfile
import com.yomitanmobile.domain.model.CardSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pronunciation sits on a card, per profile.
 *
 * Kana under the word is how a Japanese card is read, so it belongs in the
 * header. IPA is not read that way — it is a footnote — and putting /dɒɡ/
 * directly under "dog" pushed the meaning down the card for something the
 * reader rarely needs.
 */
class LatinCardLayoutTest {

    private val order = CardSection.defaultOrder()

    @Test
    fun `japanese keeps the reading in the header`() {
        val template = AnkiCardCreator.buildBackTemplate(order, CardProfile.JAPANESE)
        val headerEnd = template.indexOf("<hr>")
        assertTrue(
            "the reading should be inside the header block",
            template.indexOf("{{Reading}}") in 0 until headerEnd
        )
    }

    @Test
    fun `latin profiles move the pronunciation below the meaning`() {
        for (profile in listOf(CardProfile.ENGLISH, CardProfile.SPANISH)) {
            val template = AnkiCardCreator.buildBackTemplate(order, profile)

            val headerEnd = template.indexOf("<hr>")
            val readingAt = template.indexOf("{{Reading}}")
            val meaningAt = template.indexOf("{{Meaning}}")

            assertTrue("$profile: pronunciation missing entirely", readingAt >= 0)
            assertTrue(
                "$profile: pronunciation is still in the header",
                readingAt > headerEnd
            )
            assertTrue(
                "$profile: pronunciation should follow the meaning, not precede it",
                readingAt > meaningAt
            )
        }
    }

    @Test
    fun `latin profiles never reference a field their note type lacks`() {
        // AnkiDroid renders an unknown {{Field}} as literal text on the card,
        // so a template mentioning PitchAccent or KanjiBreakdown would print
        // the placeholder itself.
        for (profile in listOf(CardProfile.ENGLISH, CardProfile.SPANISH)) {
            val template = AnkiCardCreator.buildBackTemplate(order, profile)
            assertFalse("$profile references PitchAccent", template.contains("PitchAccent"))
            assertFalse("$profile references KanjiBreakdown", template.contains("KanjiBreakdown"))
        }
    }
}
