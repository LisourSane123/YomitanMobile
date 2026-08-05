package com.yomitanmobile.data.settings

import androidx.datastore.preferences.core.Preferences
import com.yomitanmobile.MainActivity
import com.yomitanmobile.data.ai.AI_DEFAULT_PROMPT
import com.yomitanmobile.data.ai.AiProvider
import com.yomitanmobile.domain.model.CardSection
import com.yomitanmobile.domain.model.CardStylePreferences
import com.yomitanmobile.domain.model.PitchAccentStyle

/**
 * Reads the stored card-style settings out of a DataStore snapshot.
 *
 * Single source of truth for defaults: every key falls back to the
 * [CardStylePreferences] data class's own default, so no two callers can
 * disagree about what "default" means. Shared by the detail-screen export and
 * the bulk JLPT deck generator — both must produce identically styled cards.
 */
fun readCardStylePreferences(prefs: Preferences): CardStylePreferences {
    val d = CardStylePreferences()
    return CardStylePreferences(
        expressionBold = prefs[MainActivity.CARD_EXPRESSION_BOLD] ?: d.expressionBold,
        expressionFontSize = prefs[MainActivity.CARD_EXPRESSION_FONT_SIZE] ?: d.expressionFontSize,
        readingFontSize = prefs[MainActivity.CARD_READING_FONT_SIZE] ?: d.readingFontSize,
        meaningFontSize = prefs[MainActivity.CARD_MEANING_FONT_SIZE] ?: d.meaningFontSize,
        frontContextSentenceFontSize = prefs[MainActivity.CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE]
            ?: d.frontContextSentenceFontSize,
        backSentenceFontSize = prefs[MainActivity.CARD_BACK_SENTENCE_FONT_SIZE] ?: d.backSentenceFontSize,
        fontFamily = prefs[MainActivity.CARD_FONT_FAMILY] ?: d.fontFamily,
        cardBackgroundColor = prefs[MainActivity.CARD_BACKGROUND_COLOR] ?: d.cardBackgroundColor,
        expressionColor = prefs[MainActivity.CARD_EXPRESSION_COLOR] ?: d.expressionColor,
        readingColor = prefs[MainActivity.CARD_READING_COLOR] ?: d.readingColor,
        meaningColor = prefs[MainActivity.CARD_MEANING_COLOR] ?: d.meaningColor,
        accentColor = prefs[MainActivity.CARD_ACCENT_COLOR] ?: d.accentColor,
        furiganaColor = prefs[MainActivity.CARD_FURIGANA_COLOR] ?: d.furiganaColor,
        showPitchAccent = prefs[MainActivity.CARD_SHOW_PITCH] ?: d.showPitchAccent,
        pitchAccentStyle = PitchAccentStyle.fromStorage(
            prefs[MainActivity.CARD_PITCH_ACCENT_STYLE] ?: d.pitchAccentStyle.storageValue
        ),
        showFrequency = prefs[MainActivity.CARD_SHOW_FREQUENCY] ?: d.showFrequency,
        showSentence = prefs[MainActivity.CARD_SHOW_SENTENCE] ?: d.showSentence,
        showFrontContextSentence = prefs[MainActivity.CARD_SHOW_FRONT_CONTEXT_SENTENCE]
            ?: d.showFrontContextSentence,
        randomFontsEnabled = prefs[MainActivity.CARD_RANDOM_FONTS_ENABLED] ?: d.randomFontsEnabled,
        randomFonts = prefs[MainActivity.CARD_RANDOM_FONTS] ?: d.randomFonts,
        randomVoicesEnabled = prefs[MainActivity.TTS_RANDOM_VOICES_ENABLED] ?: d.randomVoicesEnabled,
        randomVoices = prefs[MainActivity.TTS_RANDOM_VOICES] ?: d.randomVoices,
        showSectionDividers = prefs[MainActivity.CARD_SHOW_SECTION_DIVIDERS] ?: d.showSectionDividers,
        aiSummaryEnabled = prefs[MainActivity.CARD_AI_SUMMARY_ENABLED] ?: false,
        aiProvider = AiProvider.fromStorage(prefs[MainActivity.CARD_AI_PROVIDER]),
        aiApiKey = prefs[MainActivity.CARD_AI_API_KEY] ?: "",
        aiPrompt = prefs[MainActivity.CARD_AI_PROMPT] ?: AI_DEFAULT_PROMPT,
        aiModel = prefs[MainActivity.CARD_AI_MODEL] ?: "",
        sectionOrder = CardSection.decode(prefs[MainActivity.CARD_SECTION_ORDER])
    )
}
