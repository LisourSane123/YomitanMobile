package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardSection
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the contract that [AnkiCardCreator.buildBackTemplate]
 * emits the back-side sections in the order given by `sectionOrder`.
 * If this passes, any "reorder doesn't work" report is a platform-side
 * issue (AnkiDroid template cache), not a data-path issue.
 */
class AnkiBackTemplateOrderingTest {

    @Test
    fun buildBackTemplate_emitsSections_inGivenOrder() {
        val customOrder = listOf(
            CardSection.KANJI,
            CardSection.SENTENCE,
            CardSection.MEANING,
            CardSection.AUDIO,
            CardSection.PITCH,
            CardSection.SUMMARY
        )

        val html = AnkiCardCreator.buildBackTemplate(customOrder)

        // Each section type leaves a unique CSS-class fingerprint on the
        // emitted block, which lets us read order back out of the raw
        // HTML without parsing mustache.
        val markers = listOf(
            CardSection.KANJI to "kanji-breakdown",
            CardSection.SENTENCE to "{{#Sentence}}",
            CardSection.MEANING to "meaning-section",
            CardSection.AUDIO to "audio-section",
            CardSection.PITCH to "{{#PitchAccent}}",
            CardSection.SUMMARY to "summary-section"
        )

        val positions = markers.map { (section, marker) -> section to html.indexOf(marker) }
        positions.forEach { (section, idx) ->
            assertTrue("Marker for $section not found in template", idx >= 0)
        }

        // Compare actual ordering of indices against the requested order.
        val sortedBySection = positions.sortedBy { it.second }.map { it.first }
        assertTrue(
            "Expected order $customOrder but template ordering was $sortedBySection",
            sortedBySection == customOrder
        )
    }

    @Test
    fun buildBackTemplate_headerStaysPinned_evenWhenSectionsReordered() {
        val html = AnkiCardCreator.buildBackTemplate(
            listOf(
                CardSection.SUMMARY,
                CardSection.PITCH,
                CardSection.MEANING,
                CardSection.SENTENCE,
                CardSection.AUDIO,
                CardSection.KANJI
            )
        )
        // The fixed header must come before any reorderable section.
        val headerIdx = html.indexOf("header-section")
        val firstSectionIdx = html.indexOf("summary-section")
        assertTrue("header missing", headerIdx >= 0)
        assertTrue("first section missing", firstSectionIdx >= 0)
        assertTrue(
            "Header should appear before reorderable sections",
            headerIdx < firstSectionIdx
        )
    }

    @Test
    fun buildBackTemplate_doesNotRenderFrequencyInHeader() {
        // Frequency was removed from the header on user request — it
        // should no longer appear as a mustache block in the rendered
        // template, even though the field is still part of FIELD_NAMES.
        val html = AnkiCardCreator.buildBackTemplate(CardSection.defaultOrder())
        assertTrue(
            "Frequency mustache should be gone from header",
            !html.contains("{{#Frequency}}") && !html.contains("{{/Frequency}}")
        )
    }
}
