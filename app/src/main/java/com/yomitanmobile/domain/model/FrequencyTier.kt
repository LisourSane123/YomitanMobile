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

        /**
         * The starred badge ("★★★ Top 3K") for a word's [position], or "" —
         * and "" whenever [leadingValue] is blank. A tier is a claim about how
         * common a word is, and only the leading list gets to make it: a word
         * the leading list does not know shows no tier, rather than one quietly
         * borrowed from another list's scale. Past 50K there is no badge; the
         * list's own number says the rest.
         */
        fun label(position: Int, leadingValue: String): String = when {
            leadingValue.isBlank() || position <= 0 -> ""
            position <= 1_000 -> "★★★ Top 1K"
            position <= 3_000 -> "★★★ Top 3K"
            position <= 5_000 -> "★★ Top 5K"
            position <= 10_000 -> "★ Top 10K"
            position <= 20_000 -> "Top 20K"
            position <= 30_000 -> "Top 30K"
            position <= 50_000 -> "Top 50K"
            else -> ""
        }

        /** Tier a rank falls into; null for unranked words. */
        fun of(rank: Int): FrequencyTier? = when {
            rank <= 0 -> null
            else -> entries.firstOrNull { it.maxRank > 0 && rank <= it.maxRank } ?: ALL
        }
    }
}
