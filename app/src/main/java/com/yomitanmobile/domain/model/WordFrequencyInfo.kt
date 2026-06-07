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
    val displayValue: String
) {
    /** e.g. "BCCWJ #980". Falls back to the raw rank when displayValue is blank. */
    fun label(): String {
        val value = displayValue.ifBlank { rank.toString() }
        val shown = if (value.firstOrNull()?.isDigit() == true) "#$value" else value
        return "$dictionary $shown"
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
