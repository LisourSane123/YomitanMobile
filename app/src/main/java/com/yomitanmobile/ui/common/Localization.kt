package com.yomitanmobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.yomitanmobile.util.LocaleHelper
import com.yomitanmobile.util.UiLanguage

/**
 * Which interface language is showing: Polish, English or Japanese.
 *
 * The app's strings are inline `tr(pl, en, ja)` literals rather than resources
 * (see CLAUDE.md). Provided once in MainActivity.
 */
val LocalUiLanguage = compositionLocalOf { UiLanguage.POLISH }

/**
 * True for English AND Japanese: what the code that knows only two languages
 * reads, so a string with no Japanese falls back to English, not Polish.
 * Provided together with [LocalUiLanguage].
 */
val LocalIsEnglish = compositionLocalOf { false }

/**
 * The language being STUDIED (not the interface language above) — what decides
 * whether a screen offers JLPT or CEFR, a kanji browser or none. Provided once
 * in MainActivity; it only changes with a restart (LanguageSettings).
 */
val LocalStudyLanguage = compositionLocalOf { com.yomitanmobile.domain.model.AppLanguage.DEFAULT }

/** Reads the setting, or the device configuration; provide it once, at the top of the tree. */
@Composable
@ReadOnlyComposable
fun uiLanguage(): UiLanguage = LocaleHelper.language(LocalConfiguration.current)

/** The string in the interface language; a missing Japanese one falls back to English. */
@Composable
@ReadOnlyComposable
fun tr(pl: String, en: String, ja: String? = null): String = pick(LocalUiLanguage.current, pl, en, ja)

fun pick(language: UiLanguage, pl: String, en: String, ja: String?): String = when (language) {
    UiLanguage.POLISH -> pl
    UiLanguage.ENGLISH -> en
    UiLanguage.JAPANESE -> ja ?: en
}

/** [tr] as a value, for call sites that are not composable (click handlers, collect blocks). */
class Tr(val language: UiLanguage) {
    operator fun invoke(pl: String, en: String, ja: String? = null): String = pick(language, pl, en, ja)
}

@Composable
fun rememberTr(): Tr {
    val language = LocalUiLanguage.current
    return remember(language) { Tr(language) }
}
