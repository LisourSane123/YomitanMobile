package com.yomitanmobile.util

/**
 * Formats KANJIDIC on'yomi / kun'yomi reading strings for display.
 *
 * KANJIDIC separates the reading from its okurigana with an ASCII full stop
 * (e.g. `た.べる`), which renders as a low, out-of-place dot next to the kana.
 * Japanese convention uses the round katakana middle dot (nakaguro, `・`), so we
 * swap it in. The `, ` separator between multiple readings is left untouched.
 */
object KanjiReadingFormatter {

    private const val NAKAGURO = '・' // ・

    fun format(reading: String): String =
        if (reading.isEmpty()) reading else reading.replace('.', NAKAGURO)
}
