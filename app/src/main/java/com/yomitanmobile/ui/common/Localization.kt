package com.yomitanmobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.yomitanmobile.util.LocaleHelper

/**
 * Which of the two interface languages is showing.
 *
 * The app's strings are inline `tr(pl, en)` literals rather than resources (a
 * deliberate choice for two languages — see CLAUDE.md), and every screen used
 * to answer this question for itself: fifteen copies of the same two lines,
 * plus an `isEnglish` parameter threaded down through every private helper.
 */
val LocalIsEnglish = compositionLocalOf { false }

/** Reads the device configuration; provide it once, at the top of the tree. */
@Composable
@ReadOnlyComposable
fun isEnglishUi(): Boolean = LocaleHelper.isEnglish(LocalConfiguration.current)

/** The Polish string or the English one, whichever the interface is set to. */
@Composable
@ReadOnlyComposable
fun tr(pl: String, en: String): String = if (LocalIsEnglish.current) en else pl

/**
 * The same thing as a value, for the many call sites that are not composable:
 * a toast inside a click handler, a label built in a `collect` block. Screens
 * hold it as `val tr = rememberTr()` and call `tr(...)` exactly as before.
 */
@Composable
fun rememberTr(): (String, String) -> String {
    val english = LocalIsEnglish.current
    return remember(english) { { pl, en -> if (english) en else pl } }
}
