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
                filters.skipProperNames && WordFilterRules.isProperName(entry) -> {
                    reject(JlptSkipReason.PROPER_NAME); false
                }
                entry.frequency <= 0 && !filters.includeUnranked -> {
                    reject(JlptSkipReason.UNRANKED); false
                }
                filters.maxFrequencyRank > 0 &&
                    entry.frequency > filters.maxFrequencyRank -> {
                    reject(JlptSkipReason.TOO_RARE); false
                }
                filters.skipArchaic && WordFilterRules.isArchaic(entry) -> {
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

}
