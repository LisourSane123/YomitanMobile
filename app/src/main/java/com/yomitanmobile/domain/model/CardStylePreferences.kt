package com.yomitanmobile.domain.model

import com.yomitanmobile.data.ai.AI_DEFAULT_PROMPT
import com.yomitanmobile.data.ai.AiProvider

enum class PitchAccentStyle(val storageValue: String) {
    LEGACY("legacy"),
    DOT_LINE("dot_line");

    companion object {
        fun fromStorage(value: String?): PitchAccentStyle {
            return values().firstOrNull { it.storageValue == value } ?: LEGACY
        }
    }
}

/**
 * Preferences for Anki card visual styling.
 * These control how the generated HTML card looks.
 */
data class CardStylePreferences(
    val expressionBold: Boolean = false,
    val expressionFontSize: Int = 48,
    val readingFontSize: Int = 28,
    val meaningFontSize: Int = 20,
    val frontContextSentenceFontSize: Int = 20,
    val backSentenceFontSize: Int = 20,
    val fontFamily: String = "Hiragino Sans",
    val cardBackgroundColor: String = "#1a1a1a",
    val expressionColor: String = "#ffffff",
    val readingColor: String = "#ffffff",
    val meaningColor: String = "#e0e0e0",
    val accentColor: String = "#80cbc4",
    // Furigana reading color on example sentences. Empty = inherit the
    // sentence text color (i.e. same color as the text) — the default.
    val furiganaColor: String = "",
    val showPitchAccent: Boolean = true,
    val pitchAccentStyle: PitchAccentStyle = PitchAccentStyle.DOT_LINE,
    val showFrequency: Boolean = true,
    val showSentence: Boolean = true,
    val showFrontContextSentence: Boolean = true,
    val randomFontsEnabled: Boolean = true,
    val randomFonts: Set<String> = DEFAULT_RANDOM_FONTS,
    val randomVoicesEnabled: Boolean = true,
    val randomVoices: Set<String> = DEFAULT_RANDOM_VOICES,
    // When false, the back-side <hr> dividers between sections (header,
    // pitch, frequency, meaning, sentence, audio, kanji) are hidden via CSS
    // for a flatter look.
    val showSectionDividers: Boolean = false,
    // Controls the bold <hr class="word-divider"> that sits inside the header
    // between the expression and the reading. Independent of
    // [showSectionDividers] so the user can flatten the back-side sections
    // while keeping the word/reading split visible.
    val showWordDivider: Boolean = true,
    // Optional AI summary integration. When [aiSummaryEnabled] is true and
    // [aiApiKey] is non-blank, DetailViewModel calls AiSummaryService at
    // export time and renders the result in the back-side Summary slot
    // (rendered between pitch/frequency and the meaning column).
    val aiSummaryEnabled: Boolean = false,
    val aiProvider: AiProvider = AiProvider.GEMINI,
    val aiApiKey: String = "",
    val aiPrompt: String = AI_DEFAULT_PROMPT,
    /**
     * Optional override for the model name. Blank = use the provider's
     * baked-in defaultModel. Lets the user pin a specific model
     * (gemini-2.5-flash, deepseek-reasoner, gpt-4o, …) without code
     * changes.
     */
    val aiModel: String = "",
    /**
     * User-controlled order of back-side sections (pitch, summary,
     * meaning, sentence, audio, kanji). The header block above the
     * layer divider is fixed and not part of this list. Default order
     * matches [CardSection.defaultOrder].
     */
    val sectionOrder: List<CardSection> = CardSection.defaultOrder()
) {
    companion object {
        /**
         * Default pool for the random-expression-font feature: the three
         * bundled-with-Google-Fonts families the app ships with random fonts
         * enabled by default.
         */
        val DEFAULT_RANDOM_FONTS = setOf(
            "Noto Sans JP",
            "Noto Serif JP",
            "M PLUS Rounded 1c"
        )

        /**
         * Default pool for the random-TTS-voice feature: every standard Google
         * Japanese voice EXCEPT the two "htm" ones (ja-jp-x-htm-local /
         * ja-jp-x-htm-network). Voices not installed on a given device are
         * simply skipped at export time (AnkiCardCreator falls back to the
         * current voice), so listing all of them here is safe.
         */
        val DEFAULT_RANDOM_VOICES = setOf(
            "ja-jp-x-htn-local", "ja-jp-x-htn-network",
            "ja-jp-x-htf-local", "ja-jp-x-htf-network",
            "ja-jp-x-htj-local", "ja-jp-x-htj-network"
        )

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
