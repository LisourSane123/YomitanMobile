package com.yomitanmobile.data.anki

/**
 * Which kanji the user's collection is made of, and how much of each.
 *
 * The stored collection scan is a list of the WORDS the user has cards for.
 * Cut those words into characters and the same list answers a question the app
 * could not ask before: 人 sits in twenty of my words, 食 in five, and this
 * character here in exactly one. That is the order in which characters are
 * worth studying — a learner's own collection is a better frequency list for
 * them than any corpus, because it is the text they will actually review.
 *
 * Counted per WORD of the scan, and both numbers are kept because they differ
 * and each answers something:
 *
 *  * [KanjiCount.words] — how many of my words contain this character. 日曜日
 *    counts once. This is the figure the screen leads with, because it is what
 *    "I meet this character in N of my cards" means.
 *  * [KanjiCount.occurrences] — how many times it is written across them. 日曜日
 *    counts twice. This is what adds up to [KanjiTallyResult.totalOccurrences],
 *    the "how many kanji are in my collection altogether" number.
 *
 * Pure Kotlin, so the rule can be tested on a real collection dump without a
 * device ([KanjiTallyRealCollectionTest]).
 */
object KanjiTally {

    data class KanjiCount(
        val kanji: String,
        val words: Int,
        val occurrences: Int
    )

    data class KanjiTallyResult(
        /** Most words first; ties broken by the character, so two runs agree. */
        val counts: List<KanjiCount>,
        /** Words the tally read. */
        val wordCount: Int,
        /** Of those, how many carry at least one kanji. */
        val wordsWithKanji: Int
    ) {
        val distinctKanji: Int get() = counts.size
        val totalOccurrences: Int get() = counts.sumOf { it.occurrences }

        companion object {
            val EMPTY = KanjiTallyResult(emptyList(), 0, 0)
        }
    }

    fun of(words: Collection<String>): KanjiTallyResult {
        val wordsPer = HashMap<Char, Int>(2048)
        val occurrencesPer = HashMap<Char, Int>(2048)
        var withKanji = 0
        // Per word, so a character written twice in one word adds one to the
        // word count and two to the occurrences.
        val seen = HashSet<Char>(8)
        for (word in words) {
            seen.clear()
            for (ch in word) {
                if (!isKanji(ch)) continue
                occurrencesPer[ch] = (occurrencesPer[ch] ?: 0) + 1
                seen += ch
            }
            if (seen.isEmpty()) continue
            withKanji++
            for (ch in seen) wordsPer[ch] = (wordsPer[ch] ?: 0) + 1
        }
        val counts = wordsPer.map { (ch, wordHits) ->
            KanjiCount(
                kanji = ch.toString(),
                words = wordHits,
                occurrences = occurrencesPer[ch] ?: wordHits
            )
        }.sortedWith(compareByDescending<KanjiCount> { it.words }.thenBy { it.kanji })
        return KanjiTallyResult(counts, words.size, withKanji)
    }

    /**
     * CJK Unified Ideographs, and deliberately nothing else.
     *
     * [AnkiNoteFieldIndexer.isKanji] also accepts 々, ヶ and ヵ, because for
     * matching a written form they are part of the spelling (一ヶ月, 人々). They
     * are not characters to study, and counting them would put the repeat mark
     * among the commonest "kanji" in the collection. Characters outside the
     * basic block (𠀋 and friends) are left out too: they cannot appear without
     * a surrogate pair, and no card in a vocabulary deck is built on one.
     */
    fun isKanji(ch: Char): Boolean = ch in '一'..'鿿'
}
