package com.yomitanmobile.data.anki

import com.yomitanmobile.domain.model.MergedWordEntry
import com.yomitanmobile.domain.model.WordEntry
import com.yomitanmobile.domain.usecase.WordFilterRules

/**
 * What the dictionary knows about a word that its headword does not say, for
 * the duplicate check ([AnkiCollectionMatch.Index.containsAny]).
 *
 * - [otherSpellings]: the entry's other written forms. Jitendex stores every
 *   spelling as a row of its own, joined only by the JMdict sequence, so the
 *   merged entry never carries them — 身体 does not know about 体. A deck that
 *   files the word under the other spelling (Core's 体, 目, 話し掛ける) held a
 *   card the check could not see.
 * - [readingIsUnambiguous]: no OTHER word a reader could mean is read this
 *   way, so a note that writes the word in kana alone (Core's ひざ, あきれる,
 *   にらむ) is this word. "Other word" is any homophone a frequency list ranks
 *   at all, or one usually written in kana — a note holding only くれる is
 *   呉れる, not 暮れる. The bar is "ranked at all" rather than "common":
 *   ついで in a deck is 序で "on the way", whose kanji spelling is ranked
 *   168 990 because the word is written in kana, and nothing else in the
 *   data says so (Jitendex's "usually kana" for it sits on one sense, which
 *   the parser does not carry into the entry's tags).
 *
 * Measured on a real collection (3 147 mined notes beside Core 2.3k, desktop
 * Yomitan and Kanji Study notes): the headword rule found 17 of the mined
 * words already there, the other spellings 24 more, the kana reading 12 more,
 * every one of them read by hand and correct; the readings it refused include
 * every homophone pair on that collection (医師/意志, 公開/後悔, 暮れる/くれる).
 */
data class WordForms(
    val otherSpellings: List<String>,
    val readingIsUnambiguous: Boolean
) {
    /**
     * The rows that share a reading, grouped once, so a deck's worth of words
     * is answered from one query.
     *
     * @param rows every dictionary row whose reading is one of the readings
     * asked about. Their `frequency` is the rank the leading list gives them
     * (1 = commonest, 0 = unranked).
     */
    class Lookup(rows: List<WordEntry>) {
        private val byReading: Map<String, List<WordEntry>> =
            rows.groupBy { it.reading.ifBlank { it.expression } }
        private val ranksKnown = rows.any { it.frequency > 0 }

        fun of(entry: MergedWordEntry): WordForms {
            val sameReading = byReading[entry.reading].orEmpty()
            val ownSpellings = (listOf(entry.primaryExpression) + entry.alternativeExpressions).toSet()
            val ownSequences = buildSet {
                if (entry.sequenceNumber > 0) add(entry.sequenceNumber)
                sameReading.filter { it.expression in ownSpellings && it.sequenceNumber > 0 }
                    .forEach { add(it.sequenceNumber) }
            }
            // Without a sequence nothing says which rows are this word.
            if (ownSequences.isEmpty()) return NONE
            val others = sameReading
                .filter { it.sequenceNumber in ownSequences && it.expression.isNotBlank() && it.expression !in ownSpellings }
                .map { it.expression }
                .distinct()
            // Every other word read this way, one group per entry; a row with
            // no sequence is a word of its own.
            val homophones = sameReading.filter { it.sequenceNumber !in ownSequences }
                .groupBy { if (it.sequenceNumber > 0) "#${it.sequenceNumber}" else it.expression }
                .values
            // Without any frequency list, an unranked homophone could be the
            // commonest word there is, so only a reading no other entry has
            // counts.
            val unambiguous = homophones.isEmpty() || (ranksKnown && homophones.none { group ->
                group.any { it.frequency > 0 } ||
                    MergedWordEntry.mergeEntries(group).any { WordFilterRules.isUsuallyKana(it) }
            })
            return WordForms(others, unambiguous)
        }
    }

    companion object {
        val NONE = WordForms(emptyList(), readingIsUnambiguous = false)
    }
}
