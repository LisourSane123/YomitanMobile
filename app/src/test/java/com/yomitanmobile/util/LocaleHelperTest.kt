package com.yomitanmobile.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens used to read only the FIRST entry in the LocaleList, which
 * meant a user with `ja, en` (English second) saw Polish strings, and a
 * user with neither EN nor PL on the device also got Polish. The helper
 * fixes both: it scans the whole language-code list and falls back to
 * English when neither language is present.
 */
class LocaleHelperTest {

    @Test
    fun englishWhenEnglishIsFirst() {
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(listOf("en")))
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(listOf("EN")))
    }

    @Test
    fun polishWhenPolishIsFirst() {
        assertFalse(LocaleHelper.isEnglishForLanguageCodes(listOf("pl")))
        assertFalse(LocaleHelper.isEnglishForLanguageCodes(listOf("PL")))
    }

    @Test
    fun englishPicksUpWhenItComesAfterAnUnsupportedLocale() {
        // ja then en — old code only checked first locale and returned PL.
        // The fixed scan finds "en" further down and switches to English.
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(listOf("ja", "en")))
    }

    @Test
    fun polishWinsWhenItOutranksEnglish() {
        // User explicitly ranked Polish first — honor that.
        assertFalse(LocaleHelper.isEnglishForLanguageCodes(listOf("pl", "en")))
    }

    @Test
    fun englishFallbackForUnsupportedLocales() {
        // Old code: anything not "en" → Polish. New code: Spanish/Japanese
        // users without EN or PL configured see English (more readable).
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(listOf("es")))
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(listOf("ja")))
        assertTrue(LocaleHelper.isEnglishForLanguageCodes(emptyList()))
    }
}
