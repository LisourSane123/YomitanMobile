package com.yomitanmobile.domain.model

/**
 * The language the user is studying.
 *
 * This is the app's single most load-bearing setting: it decides which
 * dictionaries are offered, which rows the search sees, how a query is
 * expanded into candidates, and which Anki note type an export lands on.
 * Everything language-specific hangs off this enum rather than off scattered
 * script checks.
 *
 * Deliberately NOT auto-detected. Script detection can tell Japanese from
 * Latin input, but it cannot tell "English word I want a Polish gloss for"
 * from "Polish word I want an English headword for" — those are the same
 * characters. The user picks once on first run.
 */
enum class AppLanguage(
    /** Value persisted in DataStore. Stable — never rename. */
    val storageValue: String,
    /** Value stored on every imported row's `language` column. */
    val entryTag: String
) {
    JAPANESE("ja", "ja"),
    ENGLISH("en", "en"),
    SPANISH("es", "es");

    /**
     * Whether this language uses the Japanese-only machinery: JLPT levels
     * and their deck generator, pitch accent, furigana, kanji breakdown, the
     * longest-match text scanner and the "looks Japanese" Anki collection
     * scan. Every one of those rests on a data source or a script property
     * no other language has, so they are hidden rather than shown empty.
     */
    val hasJapaneseFeatures: Boolean get() = this == JAPANESE

    /**
     * BCP-47 tag for text-to-speech and voice selection.
     *
     * The app used to hardcode Japanese here, so an English or Spanish card
     * was read aloud by a Japanese voice — which pronounces Latin text as if
     * it were romaji, and produced TTS audio that was useless on the card.
     */
    val ttsLanguageTag: String
        get() = when (this) {
            JAPANESE -> "ja"
            ENGLISH -> "en"
            SPANISH -> "es"
        }

    /**
     * index.json title prefix of the dictionary whose glosses this language's
     * cards should prefer.
     *
     * Both non-Japanese languages install two term dictionaries: a bilingual
     * one that is the point (Polish for English, English for Spanish) and a
     * far larger monolingual one that fills the gaps. Search returns whichever
     * matched, so the card builder has to name the preferred source rather
     * than trust ordering. Null for Japanese, whose preferred source is a
     * user choice in settings instead (see MonolingualCardResolver).
     */
    val preferredGlossDictionary: String?
        get() = when (this) {
            JAPANESE -> null
            ENGLISH -> "kty-en-pl"
            SPANISH -> "kty-es-en"
        }

    companion object {
        /**
         * What an install that predates the language setting is. Every row
         * already in the database at migration time is Japanese, so this
         * default has to match the migration's DEFAULT or an upgrading user
         * would open the app to an empty dictionary.
         */
        val DEFAULT = JAPANESE

        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT

        /**
         * Maps a Yomitan `index.json` `sourceLanguage` code onto a study
         * language. Returns null for anything we don't ship support for —
         * the caller then falls back to the active language, which is the
         * right answer for the many dictionaries that omit the field
         * entirely (Jitendex, JMdict and every frequency list do).
         */
        fun fromSourceLanguageCode(code: String?): AppLanguage? =
            when (code?.trim()?.lowercase()) {
                "ja", "jp", "jpn", "japanese" -> JAPANESE
                "en", "eng", "english" -> ENGLISH
                "es", "spa", "spanish" -> SPANISH
                else -> null
            }
    }
}
