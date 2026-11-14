package com.yomitanmobile.util

import android.content.Context
import android.content.res.Configuration

/** The interface language. Independent of the study language and of the card labels. */
enum class UiLanguage(val storageValue: String) {
    POLISH("pl"), ENGLISH("en"), JAPANESE("ja");

    companion object {
        fun fromStorage(value: String?): UiLanguage? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * Centralized locale handling for the PL/EN/JA UI.
 *
 * The user's choice in settings wins ([chosen], primed in Application.onCreate
 * and updated when the setting changes); without one, the device's locale
 * list decides — every locale is scanned, the first of Japanese, Polish and
 * English wins, and English is the fallback for a language the app does not
 * speak.
 *
 * [isEnglish] stays for the code that only knows two languages: it is true for
 * Japanese too, so a string nobody translated to Japanese falls back to
 * English, not to Polish.
 */
object LocaleHelper {

    /** The language picked in settings; null follows the device. */
    @Volatile
    var chosen: UiLanguage? = null

    fun language(config: Configuration): UiLanguage {
        chosen?.let { return it }
        val locales = config.locales
        if (locales.isEmpty) return UiLanguage.ENGLISH
        return languageForCodes((0 until locales.size()).map { locales.get(it).language })
    }

    fun isEnglish(config: Configuration): Boolean = language(config) != UiLanguage.POLISH

    /** Pure logic for tests: [codes] is the ranked list of ISO 639-1 codes. */
    fun languageForCodes(codes: List<String>): UiLanguage {
        for (lang in codes) {
            when (lang.lowercase()) {
                "ja" -> return UiLanguage.JAPANESE
                "pl" -> return UiLanguage.POLISH
                "en" -> return UiLanguage.ENGLISH
            }
        }
        return UiLanguage.ENGLISH
    }

    /** Kept for the tests written against the two-language rule. */
    fun isEnglishForLanguageCodes(codes: List<String>): Boolean = languageForCodes(codes) != UiLanguage.POLISH

    fun tr(config: Configuration, polish: String, english: String, japanese: String? = null): String =
        when (language(config)) {
            UiLanguage.POLISH -> polish
            UiLanguage.ENGLISH -> english
            UiLanguage.JAPANESE -> japanese ?: english
        }

    /** Where the settings screen stores the choice ("system" | "pl" | "en" | "ja"); read synchronously. */
    const val PREFS_NAME = "lang_prefs"
    const val PREFS_KEY = "app_language"

    /**
     * Primes [chosen] from settings. Called from Application.onCreate, so a
     * service or the card builder — which see the application's configuration,
     * not the activity's overridden one — speak the chosen language too.
     */
    fun loadBlocking(context: Context) {
        chosen = UiLanguage.fromStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY, null)
        )
    }
}
