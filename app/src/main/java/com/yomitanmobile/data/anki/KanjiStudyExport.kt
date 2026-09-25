package com.yomitanmobile.data.anki

/**
 * The collection's kanji as a file Kanji Study can import.
 *
 * That app's custom-set importer takes plain text and **treats every line as a
 * new set** (its changelog, 5.2.0), with the characters run together and no
 * names — the shared WaniKani list is 60 lines, one per level. So the only two
 * decisions on this side are which characters go in and where the lines break;
 * everything after that (naming, re-sorting, splitting further) is done in the
 * app.
 *
 * ORDER. Most of the user's own words first, and the media rank breaks the
 * ties. Both halves matter, and the second is not a detail: with the minimum
 * at two words, hundreds of characters share the same count, and without a
 * second key they would be ordered by code point — which is no order at all.
 * The rank is KANJIDIC's newspaper frequency, already stored on `kanji_entries`
 * (1 = commonest of about 2 500); a character the dictionary does not rank
 * sorts last within its count, and if the whole kanji dictionary predates that
 * column every rank reads 0 and the order falls back to "my words, then the
 * character itself" — which the kanji screen already warns about.
 *
 * MINIMUM. Two words, because one is not a foothold: of one real collection's
 * 1 639 characters, 555 sat in exactly one word. Those are not characters to
 * study, they are characters to meet a second time.
 */
object KanjiStudyExport {

    /** A character in only one word has nowhere to be recognised again. */
    const val DEFAULT_MIN_WORDS = 2

    /** One screen of a study session, and what Kanji Study's own splitter offers. */
    const val DEFAULT_SET_SIZE = 20

    const val MIME_TYPE = "text/plain"
    const val FILE_NAME = "moje-kanji.txt"

    data class Plan(
        /** Characters in study order. */
        val kanji: List<String>,
        /** One string per set — a line of the file. */
        val sets: List<String>,
        val minWords: Int,
        val setSize: Int,
        /** Characters left out for sitting in fewer than [minWords] words. */
        val dropped: Int,
        /** Of [kanji], how many the kanji dictionary gives no media rank. */
        val unranked: Int
    ) {
        val isEmpty: Boolean get() = kanji.isEmpty()
    }

    fun plan(
        counts: List<KanjiTally.KanjiCount>,
        mediaRank: (String) -> Int = { 0 },
        minWords: Int = DEFAULT_MIN_WORDS,
        setSize: Int = DEFAULT_SET_SIZE
    ): Plan {
        val kept = counts.filter { it.words >= minWords }
        val ordered = kept.sortedWith(
            compareByDescending<KanjiTally.KanjiCount> { it.words }
                .thenBy { rankOrLast(mediaRank(it.kanji)) }
                .thenBy { it.kanji }
        )
        val characters = ordered.map { it.kanji }
        return Plan(
            kanji = characters,
            sets = characters.chunked(setSize.coerceAtLeast(1)) { it.joinToString("") },
            minWords = minWords,
            setSize = setSize,
            dropped = counts.size - kept.size,
            unranked = characters.count { mediaRank(it) <= 0 }
        )
    }

    /** A rank of 0 means "not ranked", which must sort last, not first. */
    private fun rankOrLast(rank: Int): Int = if (rank > 0) rank else Int.MAX_VALUE

    /**
     * The file itself: one set per line, characters with nothing between them.
     * A trailing newline, so appending another export by hand cannot glue two
     * sets into one.
     */
    fun text(plan: Plan): String =
        if (plan.sets.isEmpty()) "" else plan.sets.joinToString("\n", postfix = "\n")
}
