package com.yomitanmobile.domain.model

/**
 * Frequency bands the app filters and labels words by.
 *
 * One definition shared by the text scanner's "only make cards for the top
 * N words" switch and by the frequency badges, so a word labelled "Top 30K"
 * is exactly the word the TOP_30K tier keeps. [maxRank] is inclusive; 0 means
 * "no cut".
 */
enum class FrequencyTier(val maxRank: Int) {
    TOP_1K(1_000),
    TOP_3K(3_000),
    TOP_5K(5_000),
    TOP_10K(10_000),
    TOP_20K(20_000),
    TOP_30K(30_000),
    TOP_50K(50_000),
    ALL(0);

    /** Short label for chips: "Top 10K", "Top 30K", "bez limitu". */
    fun label(isEnglish: Boolean): String = when (this) {
        ALL -> if (isEnglish) "All" else "Wszystkie"
        else -> "Top ${maxRank / 1000}K"
    }

    companion object {
        /** Tiers offered as filter chips, commonest first. */
        val SELECTABLE = listOf(TOP_5K, TOP_10K, TOP_20K, TOP_30K, TOP_50K, ALL)

        /** Tier a rank falls into; null for unranked words. */
        fun of(rank: Int): FrequencyTier? = when {
            rank <= 0 -> null
            else -> entries.firstOrNull { it.maxRank > 0 && rank <= it.maxRank } ?: ALL
        }
    }
}
