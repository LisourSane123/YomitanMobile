package com.yomitanmobile.domain.model

/**
 * Tells a frequency list that ships RANKS from one that ships raw OCCURRENCE
 * COUNTS, from the shape of its numbers.
 *
 * Yomitan's `term_meta_bank` format has one number per word and no field
 * saying which way it runs, so the two kinds are indistinguishable by format:
 *
 *  • a rank list (JPDB, BCCWJ, CEJC) is very nearly a permutation of
 *    1..N — the largest value is about the number of words in it, and almost
 *    every value occurs once,
 *  • a count list (Innocent Corpus, the hand-built "word count" lists) is a
 *    long tail — 300 000 words with values up to several million, and tens of
 *    thousands of words sharing the count 1.
 *
 * Getting this wrong is not cosmetic: the app reads the column as "lower is
 * better" everywhere, so a count list read as ranks says the commonest word in
 * Japanese is the rarest one — it would sink to the bottom of every search,
 * be dropped as "too rare" by both deck generators, and reach the card with a
 * number an Anki reorder addon sorts backwards.
 *
 * The detector is deliberately conservative: a list has to look clearly unlike
 * a rank list before it is treated as counted, because the user can flip the
 * answer on the frequency screen and the mis-detection they cannot see is the
 * one that gets shipped.
 */
object FrequencyDirection {

    /** A list smaller than this says too little; assume it is ranked. */
    private const val MIN_ROWS = 50

    /** Counts run far past the row count; ranks stop at about it. */
    private const val SPREAD_FACTOR = 5

    /** Ranks are nearly unique; counts pile up on 1, 2, 3… */
    private const val TIE_FACTOR = 5

    /** Names that say outright what the numbers are. */
    private val COUNT_NAME_HINTS = listOf("count", "occurrence", "innocent corpus")

    /**
     * True when [stats] describe occurrence counts (higher = commoner).
     *
     * [rowCount] is the number of ranked words, [distinctValues] how many
     * different numbers they carry, [maxValue] the largest of them.
     */
    fun isCountBased(
        dictionaryName: String,
        rowCount: Int,
        distinctValues: Int,
        maxValue: Int
    ): Boolean {
        val name = dictionaryName.lowercase()
        if (COUNT_NAME_HINTS.any { it in name }) return true
        if (rowCount < MIN_ROWS) return false
        // A rank list's top value is its own length. Anything reaching far
        // past that is counting something.
        if (maxValue > rowCount.toLong() * SPREAD_FACTOR) return true
        // …and a list whose values repeat heavily is not handing out ranks,
        // even when its numbers happen to stay small.
        return distinctValues > 0 && rowCount / distinctValues >= TIE_FACTOR
    }
}
