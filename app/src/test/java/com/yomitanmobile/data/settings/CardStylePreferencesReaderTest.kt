package com.yomitanmobile.data.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.yomitanmobile.MainActivity
import com.yomitanmobile.domain.model.CardStylePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader is the only path from stored settings to an exported card, so a
 * key it forgets is a switch the user can flip that changes nothing —
 * `card_show_word_divider` was exactly that for as long as the preference
 * existed. These tests fail if a preference is added to the screen and not to
 * the reader, and if a hand-edited settings.json can push markup into the card
 * through a colour or font slot.
 */
class CardStylePreferencesReaderTest {

    @Test
    fun `every stored preference reaches the card`() {
        val prefs = mutablePreferencesOf().apply {
            set(MainActivity.CARD_EXPRESSION_BOLD, true)
            set(MainActivity.CARD_EXPRESSION_FONT_SIZE, 41)
            set(MainActivity.CARD_READING_FONT_SIZE, 42)
            set(MainActivity.CARD_MEANING_FONT_SIZE, 43)
            set(MainActivity.CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE, 44)
            set(MainActivity.CARD_BACK_SENTENCE_FONT_SIZE, 45)
            set(MainActivity.CARD_FONT_FAMILY, "Noto Serif JP")
            set(MainActivity.CARD_BACKGROUND_COLOR, "#101010")
            set(MainActivity.CARD_EXPRESSION_COLOR, "#202020")
            set(MainActivity.CARD_READING_COLOR, "#303030")
            set(MainActivity.CARD_MEANING_COLOR, "#404040")
            set(MainActivity.CARD_ACCENT_COLOR, "#505050")
            set(MainActivity.CARD_FURIGANA_COLOR, "#606060")
            set(MainActivity.CARD_SHOW_PITCH, false)
            set(MainActivity.CARD_SHOW_FREQUENCY, true)
            set(MainActivity.CARD_SHOW_SENTENCE, false)
            set(MainActivity.CARD_SHOW_FRONT_CONTEXT_SENTENCE, false)
            set(MainActivity.CARD_RANDOM_FONTS_ENABLED, false)
            set(MainActivity.TTS_RANDOM_VOICES_ENABLED, false)
            set(MainActivity.CARD_SHOW_SECTION_DIVIDERS, true)
            set(MainActivity.CARD_SHOW_WORD_DIVIDER, false)
        }

        val read = readCardStylePreferences(prefs)

        assertEquals(true, read.expressionBold)
        assertEquals(41, read.expressionFontSize)
        assertEquals(42, read.readingFontSize)
        assertEquals(43, read.meaningFontSize)
        assertEquals(44, read.frontContextSentenceFontSize)
        assertEquals(45, read.backSentenceFontSize)
        assertEquals("Noto Serif JP", read.fontFamily)
        assertEquals("#101010", read.cardBackgroundColor)
        assertEquals("#202020", read.expressionColor)
        assertEquals("#303030", read.readingColor)
        assertEquals("#404040", read.meaningColor)
        assertEquals("#505050", read.accentColor)
        assertEquals("#606060", read.furiganaColor)
        assertEquals(false, read.showPitchAccent)
        assertEquals(true, read.showFrequency)
        assertEquals(false, read.showSentence)
        assertEquals(false, read.showFrontContextSentence)
        assertEquals(false, read.randomFontsEnabled)
        assertEquals(false, read.randomVoicesEnabled)
        assertEquals(true, read.showSectionDividers)
        assertEquals(false, read.showWordDivider)
    }

    @Test
    fun `a hostile settings file cannot inject through colours or fonts`() {
        val d = CardStylePreferences()
        val prefs = mutablePreferencesOf().apply {
            set(MainActivity.CARD_ACCENT_COLOR, "red; } body { background: url(http://evil) } .x {")
            set(MainActivity.CARD_BACKGROUND_COLOR, "#zzzzzz")
            set(MainActivity.CARD_FONT_FAMILY, "x'\"><script>alert(1)</script>")
            set(
                MainActivity.CARD_RANDOM_FONTS,
                setOf("Noto Sans JP", "evil', sans-serif; } * { display:none } .y {")
            )
        }

        val read = readCardStylePreferences(prefs)

        assertEquals(d.accentColor, read.accentColor)
        assertEquals(d.cardBackgroundColor, read.cardBackgroundColor)
        assertEquals(d.fontFamily, read.fontFamily)
        assertEquals(setOf("Noto Sans JP"), read.randomFonts)
    }

    @Test
    fun `an empty furigana colour still means inherit`() {
        val prefs = mutablePreferencesOf().apply {
            set(MainActivity.CARD_FURIGANA_COLOR, "")
        }
        assertTrue(readCardStylePreferences(prefs).furiganaColor.isEmpty())
    }
}
