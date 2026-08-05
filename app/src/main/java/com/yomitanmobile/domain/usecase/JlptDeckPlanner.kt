package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.JlptDeckFilters
import com.yomitanmobile.domain.model.JlptDeckPlan
import com.yomitanmobile.domain.model.JlptSkipReason
import com.yomitanmobile.domain.model.MergedWordEntry

/**
 * Turns "every word tagged N4" into the list of cards actually worth
 * creating. Pure Kotlin so the filter rules stay unit-testable — the
 * database, AnkiDroid and DataStore all sit behind the two predicates.
 *
 * Rules are applied in a fixed order and the FIRST one that rejects a word
 * owns the skip counter, so the numbers the UI shows add up to the candidate
 * count exactly once (a rare archaism is reported as "too rare", not twice).
 */
object JlptDeckPlanner {

    /**
     * Sense-level usage tags (WordEntry.usageTags carries the expanded English
     * form) and raw JMdict tags that mark a word as not-worth-learning.
     */
    private val ARCHAIC_TAGS = setOf(
        "arch", "archaic", "obs", "obsolete", "obsc", "obscure",
        "rare", "rarely-used", "dated", "dated term", "ok", "oik"
    )

    /** JMnedict / name-dictionary part-of-speech tags. */
    private val NAME_TAGS = setOf(
        "surname", "place", "unclass", "company", "product", "work",
        "masc", "fem", "given", "person", "organization", "station",
        "creat", "char", "dei", "doc", "ev", "fict", "group", "leg",
        "myth", "obj", "serv", "relig", "oth"
    )

    /** Dictionary names that only ever contain proper names. */
    private val NAME_DICTIONARIES = listOf("jmnedict", "enamdict", "names")

    fun plan(
        level: Int,
        candidates: List<MergedWordEntry>,
        filters: JlptDeckFilters,
        isInAnki: (MergedWordEntry) -> Boolean = { false },
        isMined: (MergedWordEntry) -> Boolean = { false },
        ankiScanUnavailable: Boolean = false,
        scannedNoteCount: Int = 0
    ): JlptDeckPlan {
        val skipped = linkedMapOf<JlptSkipReason, Int>()
        fun reject(reason: JlptSkipReason) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }

        val kept = candidates.filter { entry ->
            when {
                entry.definitions.none { it.isNotBlank() } -> {
                    reject(JlptSkipReason.NO_DEFINITION); false
                }
                filters.skipProperNames && isProperName(entry) -> {
                    reject(JlptSkipReason.PROPER_NAME); false
                }
                entry.frequency <= 0 && !filters.includeUnranked -> {
                    reject(JlptSkipReason.UNRANKED); false
                }
                filters.maxFrequencyRank > 0 &&
                    entry.frequency > filters.maxFrequencyRank -> {
                    reject(JlptSkipReason.TOO_RARE); false
                }
                filters.skipArchaic && isArchaic(entry) -> {
                    reject(JlptSkipReason.ARCHAIC); false
                }
                filters.skipAlreadyInAnki && isInAnki(entry) -> {
                    reject(JlptSkipReason.ALREADY_IN_ANKI); false
                }
                filters.skipAlreadyMined && isMined(entry) -> {
                    reject(JlptSkipReason.ALREADY_MINED); false
                }
                else -> true
            }
        }.sortedWith(byUsefulness)

        val selected = if (filters.maxWords > 0 && kept.size > filters.maxWords) {
            skipped[JlptSkipReason.OVER_LIMIT] = kept.size - filters.maxWords
            kept.take(filters.maxWords)
        } else {
            kept
        }

        return JlptDeckPlan(
            level = level,
            candidateCount = candidates.size,
            selected = selected,
            skipped = skipped,
            ankiScanUnavailable = ankiScanUnavailable,
            scannedNoteCount = scannedNoteCount
        )
    }

    /**
     * Most useful first: ranked words ahead of unranked ones, commonest
     * first. A capped deck then keeps the words worth learning, and the deck
     * is created in a sensible study order.
     */
    private val byUsefulness = compareBy<MergedWordEntry>(
        { if (it.frequency > 0) 0 else 1 },
        { if (it.frequency > 0) it.frequency else Int.MAX_VALUE },
        { it.primaryExpression.ifBlank { it.reading } }
    )

    private fun isArchaic(entry: MergedWordEntry): Boolean =
        entry.usageTags.any { it.normalizeTag() in ARCHAIC_TAGS } ||
            entry.posTokens().any { it in ARCHAIC_TAGS }

    private fun isProperName(entry: MergedWordEntry): Boolean {
        // Name dictionaries tag EVERY sense; a normal word that merely also
        // exists as a surname keeps its regular part-of-speech tags too, so
        // only an all-name tag set counts as a proper name.
        val tags = entry.posTokens()
        if (tags.isNotEmpty()) return tags.all { it in NAME_TAGS }
        // No usable tags: fall back to where the entry came from. Only decides
        // the untagged case, because MergedWordEntry keeps a single dictionary
        // name for the whole group — an ordinary word that JMnedict also lists
        // as a surname must not be dropped just because that row was grouped in.
        val fromDictionary = entry.dictionaryName.lowercase()
        return NAME_DICTIONARIES.any { fromDictionary.contains(it) }
    }

    /**
     * Individual part-of-speech tags.
     *
     * [MergedWordEntry.partsOfSpeech] is NOT a token list: each element is one
     * source entry's whole tag string as the parser joined it ("n, v5r, uk").
     * Matching those strings against a token set never fires, which is why the
     * archaic and proper-name filters silently passed everything through
     * before. Split on the separators the parser uses (comma, semicolon and
     * whitespace) and match token by token.
     */
    private fun MergedWordEntry.posTokens(): List<String> =
        partsOfSpeech.flatMap { it.split(',', ';', ' ', '\t', '\n') }
            .map { it.normalizeTag() }
            .filter { it.isNotBlank() }

    private fun String.normalizeTag(): String = trim().lowercase()
}
