package com.yomitanmobile.domain.model

/**
 * A single kanji's reading/meaning breakdown, used by the detail screen's
 * "Kanji" card. Mirrors the data the Anki export renders in its
 * KanjiBreakdown field — same source ([com.yomitanmobile.data.local.entity.KanjiEntry]),
 * same shape — so the on-screen lookup and the exported card agree.
 */
data class KanjiInfo(
    val kanji: String,
    val onyomi: String,
    val kunyomi: String,
    val meanings: List<String>
)
