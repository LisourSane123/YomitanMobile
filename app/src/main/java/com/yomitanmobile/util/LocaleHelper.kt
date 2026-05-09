package com.yomitanmobile.util

import android.content.res.Configuration

/**
 * Centralized locale handling for the EN/PL UI. The screens were detecting
 * the language inline with
 *
 *   LocalConfiguration.current.locales.get(0).language.equals("en", true)
 *
 * which has two problems:
 *   1. It only inspects the *first* locale in the LocaleList. A user whose
 *      Android settings list English second (e.g. "ja, en") would always
 *      fall through to Polish.
 *   2. Anything other than English defaulted to Polish, including languages
 *      we don't translate to (e.g. a Spanish user would see Polish text).
 *
 * [isEnglish] now scans every locale on the device and treats English as the
 * default fallback when neither English nor Polish is configured. Polish
 * only takes precedence when it appears *before* English in the LocaleList,
 * matching what users expect from Android's per-app locale ranking.
 */
object LocaleHelper {

    fun isEnglish(config: Configuration): Boolean {
        val locales = config.locales
        if (locales.isEmpty) return true
        val codes = (0 until locales.size()).map { locales.get(it).language }
        return isEnglishForLanguageCodes(codes)
    }

    /**
     * Pure logic separated so unit tests don't need Android's Configuration
     * / LocaleList types. [codes] is the ranked list of ISO 639-1 language
     * codes — first entry is the user's preferred language.
     */
    fun isEnglishForLanguageCodes(codes: List<String>): Boolean {
        for (lang in codes) {
            when {
                lang.equals("en", ignoreCase = true) -> return true
                lang.equals("pl", ignoreCase = true) -> return false
            }
        }
        return true
    }

    /**
     * Convenience translator that picks Polish when the device is configured
     * for Polish, English otherwise. Mirrors the inline `tr(pl, en)` pattern
     * the screens were using.
     */
    fun tr(config: Configuration, polish: String, english: String): String {
        return if (isEnglish(config)) english else polish
    }
}
