package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.JlptDeckFilters
import com.yomitanmobile.domain.model.JlptDeckPlan
import com.yomitanmobile.domain.model.JlptSkipReason
import com.yomitanmobile.domain.model.MergedWordEntry
import kotlin.random.Random

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
        scannedWordCount: Int = 0,
        scannedAt: Long = 0L,
        /** Injectable so the shuffle of unranked words is reproducible in tests. */
        random: Random = Random.Default
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
        }.let { words ->
            // Ranked words first, commonest first — that is the study order,
            // because AnkiDroid introduces new cards in the order they were
            // written. Words no frequency dictionary ranks cannot join that
            // ordering, so they go last; SHUFFLED rather than alphabetical,
            // because sorting them by expression puts あ-words on the first
            // fifty cards and わ-words on the last fifty, which is the one
            // order a vocabulary deck must not have.
            val (ranked, unranked) = words.partition { it.frequency > 0 }
            ranked.sortedWith(byFrequency) + unranked.shuffled(random)
        }

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
            scannedWordCount = scannedWordCount,
            scannedAt = scannedAt
        )
    }

    /**
     * Commonest first among the words that carry a rank. The expression is
     * only a tiebreaker for words that share a rank, so the order stays
     * stable between two runs of the same analysis.
     */
    private val byFrequency = compareBy<MergedWordEntry>(
        { it.frequency },
        { it.primaryExpression.ifBlank { it.reading } }
    )

}
