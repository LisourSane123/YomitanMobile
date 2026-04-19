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
    val showSentence: Boolean = true,
    val showFrontContextSentence: Boolean = false,
    val randomFontsEnabled: Boolean = false,
    val randomFonts: Set<String> = emptySet(),
    val randomVoicesEnabled: Boolean = false,
    val randomVoices: Set<String> = emptySet(),
    val useOnlineSentenceApi: Boolean = false,
    val onlineSentenceApiConsentGranted: Boolean = false
) {
    companion object {
        val FONT_FAMILIES = listOf(
            "Noto Sans JP",
            "Noto Serif JP",
            "M PLUS Rounded 1c",
            "M PLUS 1p",
            "Kosugi Maru",
            "Sawarabi Gothic",
            "Sawarabi Mincho",
            "sans-serif",
            "serif"
        )

        /**
         * Returns a Google Fonts CSS import URL for a given font, or null if it's a system font.
         */
        fun googleFontsImportUrl(fontFamily: String): String? {
            val googleFonts = mapOf(
                "Noto Sans JP" to "Noto+Sans+JP:wght@400;700",
                "Noto Serif JP" to "Noto+Serif+JP:wght@400;700",
                "M PLUS Rounded 1c" to "M+PLUS+Rounded+1c:wght@400;700",
                "M PLUS 1p" to "M+PLUS+1p:wght@400;700",
                "Kosugi Maru" to "Kosugi+Maru",
                "Sawarabi Gothic" to "Sawarabi+Gothic",
                "Sawarabi Mincho" to "Sawarabi+Mincho"
            )
            val param = googleFonts[fontFamily] ?: return null
            return "https://fonts.googleapis.com/css2?family=$param&display=swap"
        }
    }
}
