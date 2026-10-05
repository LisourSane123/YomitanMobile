package com.yomitanmobile.data.settings

import androidx.datastore.preferences.core.Preferences
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
        expressionBold = prefs[PreferenceKeys.CARD_EXPRESSION_BOLD] ?: d.expressionBold,
        expressionFontSize = prefs[PreferenceKeys.CARD_EXPRESSION_FONT_SIZE] ?: d.expressionFontSize,
        readingFontSize = prefs[PreferenceKeys.CARD_READING_FONT_SIZE] ?: d.readingFontSize,
        meaningFontSize = prefs[PreferenceKeys.CARD_MEANING_FONT_SIZE] ?: d.meaningFontSize,
        frontContextSentenceFontSize = prefs[PreferenceKeys.CARD_FRONT_CONTEXT_SENTENCE_FONT_SIZE]
            ?: d.frontContextSentenceFontSize,
        backSentenceFontSize = prefs[PreferenceKeys.CARD_BACK_SENTENCE_FONT_SIZE] ?: d.backSentenceFontSize,
        fontFamily = safeFontName(prefs[PreferenceKeys.CARD_FONT_FAMILY], d.fontFamily),
        cardBackgroundColor = safeColor(prefs[PreferenceKeys.CARD_BACKGROUND_COLOR], d.cardBackgroundColor),
        expressionColor = safeColor(prefs[PreferenceKeys.CARD_EXPRESSION_COLOR], d.expressionColor),
        readingColor = safeColor(prefs[PreferenceKeys.CARD_READING_COLOR], d.readingColor),
        meaningColor = safeColor(prefs[PreferenceKeys.CARD_MEANING_COLOR], d.meaningColor),
        accentColor = safeColor(prefs[PreferenceKeys.CARD_ACCENT_COLOR], d.accentColor),
        // Empty means "inherit the sentence colour" and is a valid value here.
        furiganaColor = (prefs[PreferenceKeys.CARD_FURIGANA_COLOR] ?: d.furiganaColor)
            .let { if (it.isBlank()) "" else safeColor(it, d.furiganaColor) },
        showPitchAccent = prefs[PreferenceKeys.CARD_SHOW_PITCH] ?: d.showPitchAccent,
        pitchAccentStyle = PitchAccentStyle.fromStorage(
            prefs[PreferenceKeys.CARD_PITCH_ACCENT_STYLE] ?: d.pitchAccentStyle.storageValue
        ),
        showFrequency = prefs[PreferenceKeys.CARD_SHOW_FREQUENCY] ?: d.showFrequency,
        showSentence = prefs[PreferenceKeys.CARD_SHOW_SENTENCE] ?: d.showSentence,
        showFrontContextSentence = prefs[PreferenceKeys.CARD_SHOW_FRONT_CONTEXT_SENTENCE]
            ?: d.showFrontContextSentence,
        randomFontsEnabled = prefs[PreferenceKeys.CARD_RANDOM_FONTS_ENABLED] ?: d.randomFontsEnabled,
        randomFonts = (prefs[PreferenceKeys.CARD_RANDOM_FONTS] ?: d.randomFonts)
            .mapNotNull { font -> font.takeIf { isSafeFontName(it) } }
            .toSet(),
        randomVoicesEnabled = prefs[PreferenceKeys.TTS_RANDOM_VOICES_ENABLED] ?: d.randomVoicesEnabled,
        randomVoices = prefs[PreferenceKeys.TTS_RANDOM_VOICES] ?: d.randomVoices,
        showSectionDividers = prefs[PreferenceKeys.CARD_SHOW_SECTION_DIVIDERS] ?: d.showSectionDividers,
        // Was missing here while the card-style screen wrote it and its own
        // preview honoured it — so turning the word divider off changed the
        // preview and nothing about the exported card.
        showWordDivider = prefs[PreferenceKeys.CARD_SHOW_WORD_DIVIDER] ?: d.showWordDivider,
        aiSummaryEnabled = prefs[PreferenceKeys.CARD_AI_SUMMARY_ENABLED] ?: false,
        aiProvider = AiProvider.fromStorage(prefs[PreferenceKeys.CARD_AI_PROVIDER]),
        aiApiKey = prefs[PreferenceKeys.CARD_AI_API_KEY] ?: "",
        aiPrompt = prefs[PreferenceKeys.CARD_AI_PROMPT] ?: AI_DEFAULT_PROMPT,
        aiModel = prefs[PreferenceKeys.CARD_AI_MODEL] ?: "",
        sectionOrder = CardSection.decode(prefs[PreferenceKeys.CARD_SECTION_ORDER])
    )
}

/**
 * Card colours and font names are interpolated straight into the CSS and the
 * HTML that get written into the AnkiDroid collection — and from there sync to
 * desktop Anki, whose templates run JavaScript. Every value the app itself
 * writes is a picker result and safe, but [com.yomitanmobile.data.backup.BackupManager.importSettings]
 * lets a user-picked settings.json set any DataStore key to any string. These
 * two guards are what stops such a file from injecting markup through the
 * style slots; an unrecognised value falls back to the default rather than
 * being escaped, because there is no legitimate colour or font it can be.
 */
private fun safeColor(value: String?, fallback: String): String {
    val candidate = value?.trim().orEmpty()
    return if (candidate.matches(HEX_COLOR)) candidate else fallback
}

private fun safeFontName(value: String?, fallback: String): String =
    if (value != null && isSafeFontName(value)) value.trim() else fallback

private fun isSafeFontName(value: String): Boolean {
    val candidate = value.trim()
    return candidate.isNotEmpty() && candidate.length <= 40 && candidate.matches(FONT_NAME)
}

private val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** Letters, digits, spaces and the punctuation real font families use. */
private val FONT_NAME = Regex("^[A-Za-z0-9 +._-]+$")
