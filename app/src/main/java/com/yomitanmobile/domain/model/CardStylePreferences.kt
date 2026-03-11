package com.yomitanmobile.domain.model

/**
 * Preferences for Anki card visual styling.
 * These control how the generated HTML card looks.
 */
data class CardStylePreferences(
    val expressionBold: Boolean = true,
    val expressionFontSize: Int = 48,
    val readingFontSize: Int = 28,
    val meaningFontSize: Int = 20,
    val fontFamily: String = "Hiragino Sans",
    val cardBackgroundColor: String = "#1a1a1a",
    val expressionColor: String = "#ffffff",
    val readingColor: String = "#80cbc4",
    val meaningColor: String = "#e0e0e0",
    val accentColor: String = "#80cbc4",
    val showPitchAccent: Boolean = true,
    val showFrequency: Boolean = true,
    val showSentence: Boolean = true
) {
    companion object {
        val FONT_FAMILIES = listOf(
            "Hiragino Sans",
            "Yu Gothic",
            "Meiryo",
            "Noto Sans JP",
            "Noto Serif JP",
            "MS Gothic",
            "MS Mincho",
            "sans-serif",
            "serif"
        )
    }
}
