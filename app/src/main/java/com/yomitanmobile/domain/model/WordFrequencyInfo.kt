package com.yomitanmobile.domain.model

/**
 * UI-facing frequency entry: one installed list's rank for a word.
 *
 * [displayValue] is what to render (usually the rank, sometimes a bucketed
 * label the source list shipped). [rank] is the numeric value used for the
 * "best frequency" rollup and tie-breaking.
 */
data class WordFrequencyInfo(
    val dictionary: String,
    val rank: Int,
    val displayValue: String,
    /**
     * True when this list shipped occurrence counts rather than ranks, so its
     * own number runs the other way (see [FrequencyDirection]). [rank] is the
     * position derived from it and is comparable with every other list's;
     * [displayValue] is still the count, and has to be rendered as one.
     */
    val higherIsBetter: Boolean = false
) {
    /** e.g. "BCCWJ #980", or "Innocent 12345×" for a counted list. */
    fun label(): String = "$dictionary ${value()}"

    /** The number alone, marked for the direction its list runs in. */
    fun value(): String {
        val value = displayValue.ifBlank { rank.toString() }
        if (value.firstOrNull()?.isDigit() != true) return value
        return if (higherIsBetter) "${value}×" else "#$value"
    }

    /**
     * The list's name cut down to what fits beside a word in a result list:
     * "BCCWJ_SUW_LUW_combined" is the publisher's file name, and the first
     * word of it is the part anyone reads.
     */
    fun shortDictionary(): String {
        val head = dictionary.split('_', '-', ' ', '(').firstOrNull().orEmpty().ifBlank { dictionary }
        return if (head.length > 10) head.take(10) else head
    }

    companion object {
        /**
         * Orders frequency entries by the user's priority list (dictionary
         * names, highest priority first). Entries from lists not in the
         * priority order keep their original relative order at the end.
         * When [showAll] is false only the single top-priority entry is kept.
         */
        fun order(
            entries: List<WordFrequencyInfo>,
            priority: List<String>,
            showAll: Boolean
        ): List<WordFrequencyInfo> {
            if (entries.isEmpty()) return emptyList()
            val rank = priority.withIndex().associate { (i, name) -> name to i }
            val ordered = entries.sortedBy { rank[it.dictionary] ?: Int.MAX_VALUE }
            return if (showAll) ordered else ordered.take(1)
        }
    }
}
