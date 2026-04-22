package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.PitchAccentStyle
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiPitchAccentPreviewTest {

    @Test
    fun buildPreviewHtml_usesDotLinePitchPattern_whenSelected() {
        val html = AnkiCardCreator.buildPreviewHtml(
            CardStylePreferences(pitchAccentStyle = PitchAccentStyle.DOT_LINE)
        )

        assertTrue(html.contains("●"))
        assertTrue(html.contains("╲"))
        assertTrue(html.contains("た"))
    }

    @Test
    fun buildPreviewHtml_usesLegacyPitchPattern_whenSelected() {
        val html = AnkiCardCreator.buildPreviewHtml(
            CardStylePreferences(pitchAccentStyle = PitchAccentStyle.LEGACY)
        )

        assertTrue(html.contains("border-top:2px solid"))
    }
}
