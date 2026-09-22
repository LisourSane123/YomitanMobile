package com.yomitanmobile.domain.model

import com.yomitanmobile.util.JlptLevelUtil

/**
 * CEFR, the scale English is taught by — A1 (first words) to C2 (mastery).
 *
 * Stored in the same `jlpt_level` column JLPT uses, with the same convention:
 * the higher the number, the EASIER the level (JLPT 5 = N5; here 6 = A1), so
 * the rollup's "several lists tag a word — the easiest level wins" (MAX) holds
 * for both scales without knowing which one it is. A row's language says which
 * scale its number is on; no row carries both.
 */
enum class CefrLevel(val label: String, val dbValue: Int, val color: Long) {
    A1("A1", 6, 0xFF4CAF50),
    A2("A2", 5, 0xFF8BC34A),
    B1("B1", 4, 0xFFFFC107),
    B2("B2", 3, 0xFFFF9800),
    C1("C1", 2, 0xFFF44336),
    C2("C2", 1, 0xFF9C27B0);

    companion object {
        fun fromDbValue(value: Int): CefrLevel? = entries.firstOrNull { it.dbValue == value }

        /** "A1"…"C2", any case; null for anything else. */
        fun fromLabel(label: String): CefrLevel? {
            val cleaned = label.trim().uppercase()
            return entries.firstOrNull { it.label == cleaned }
        }
    }
}

/**
 * The level badge a word gets, on the scale of the language being studied:
 * JLPT for Japanese, CEFR for English, none for Spanish (no open list exists).
 * The one place that reads the stored number, so a CEFR code can never be
 * drawn as "N3".
 */
object StudyLevel {

    data class Badge(val scale: String, val label: String, val color: Long)

    fun badge(language: AppLanguage, value: Int): Badge? = when (language) {
        AppLanguage.JAPANESE -> JlptLevelUtil.fromDbValue(value)?.let { Badge("JLPT", it.label, it.color) }
        AppLanguage.ENGLISH -> CefrLevel.fromDbValue(value)?.let { Badge("CEFR", it.label, it.color) }
        AppLanguage.SPANISH -> null
    }
}
