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
    ENGLISH("en", "en");

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
                else -> null
            }
    }
}
