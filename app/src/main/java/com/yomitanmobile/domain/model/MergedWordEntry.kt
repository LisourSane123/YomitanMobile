package com.yomitanmobile.domain.model

/**
 * Represents a merged/consolidated search result.
 * Multiple [WordEntry] items with the same reading are grouped into one.
 */
data class MergedWordEntry(
    val primaryId: Long,
    val primaryExpression: String,
    val reading: String,
    val definitions: List<String>,
    val alternativeExpressions: List<String>,
    val frequency: Int = 0,
    val pitchAccent: String = "",
    val partsOfSpeech: List<String> = emptyList(),
    val dictionaryName: String = "",
    val entryIds: List<Long> = emptyList(),
    val exampleSentence: String = "",
    val exampleSentenceTranslation: String = "",
    val audioFile: String = ""
) {
    fun displayText(): String = primaryExpression.ifBlank { reading }

    fun definitionText(): String = definitions.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("; ")

    fun definitionTextShort(): String {
        val joined = definitions.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("; ")
        return if (joined.length > 120) joined.take(117) + "..." else joined
    }

    fun frequencyLabel(): String = when {
        frequency <= 0 -> ""
        frequency <= 1000 -> "★★★ Top 1K"
        frequency <= 3000 -> "★★★ Top 3K"
        frequency <= 5000 -> "★★ Top 5K"
        frequency <= 10000 -> "★ Top 10K"
        frequency <= 20000 -> "Top 20K"
        frequency <= 50000 -> "Top 50K"
        else -> "#$frequency"
    }

    /**
     * Convert back to a single WordEntry (for Anki export compatibility).
     */
    fun toWordEntry(): WordEntry = WordEntry(
        id = primaryId,
        expression = primaryExpression,
        reading = reading,
        definitions = definitions,
        frequency = frequency,
        pitchAccent = pitchAccent,
        partsOfSpeech = partsOfSpeech.joinToString(", "),
        dictionaryName = dictionaryName,
        exampleSentence = exampleSentence,
        exampleSentenceTranslation = exampleSentenceTranslation,
        audioFile = audioFile
    )

    companion object {
        /**
         * Checks if a character is a CJK kanji.
         */
        private fun isKanji(c: Char): Boolean {
            val type = Character.UnicodeBlock.of(c)
            return type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                    type == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        }

        /**
         * Returns true if the string contains at least one kanji character.
         */
        private fun containsKanji(s: String): Boolean = s.any { isKanji(it) }

        /**
         * Merge a list of [WordEntry] items into consolidated [MergedWordEntry] items.
         * Groups entries by reading (hiragana). Within each group:
         * - Picks the best expression (prefers kanji forms, then frequency)
         * - Collects all unique alternative expressions
         * - Merges all unique definitions
         * - Takes the best frequency, first non-empty pitch accent, etc.
         */
        fun mergeEntries(entries: List<WordEntry>): List<MergedWordEntry> {
            if (entries.isEmpty()) return emptyList()

            // Use LinkedHashMap to preserve order of first appearance (from SQL query)
            val groups = LinkedHashMap<String, MutableList<WordEntry>>()
            for (entry in entries) {
                val key = entry.reading.ifBlank { entry.expression }
                groups.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return groups.map { (reading, group) ->
                // Sort: prefer entries with kanji and frequency
                val sorted = group.sortedWith(
                    compareByDescending<WordEntry> { containsKanji(it.expression) }
                        .thenBy { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }
                )

                    val primary = sorted.first()

                    // Collect all unique expressions
                    val allExpressions = group.map { it.expression }
                        .filter { it.isNotBlank() }
                        .distinct()

                    // Primary expression is the best one (kanji preferred)
                    val primaryExpression = primary.expression

                    // Alternative expressions (excluding the primary)
                    val alternatives = allExpressions
                        .filter { it != primaryExpression }

                    // Merge all definitions, deduplicate
                    val allDefinitions = group
                        .flatMap { it.definitions }
                        .filter { it.isNotBlank() }
                        .distinct()

                    // Merge parts of speech, deduplicate
                    val allPartsOfSpeech = group
                        .map { it.partsOfSpeech }
                        .filter { it.isNotBlank() }
                        .distinct()

                    // Best frequency (lowest positive number)
                    val bestFrequency = group
                        .map { it.frequency }
                        .filter { it > 0 }
                        .minOrNull() ?: 0

                    // First non-empty pitch accent
                    val pitchAccent = group
                        .map { it.pitchAccent }
                        .firstOrNull { it.isNotBlank() } ?: ""

                    // First non-empty example sentence
                    val example = group.firstOrNull {
                        it.exampleSentence.isNotBlank()
                    }

                    // First non-empty audio
                    val audioFile = group
                        .map { it.audioFile }
                        .firstOrNull { it.isNotBlank() } ?: ""

                    // First non-empty dictionary name
                    val dictionaryName = group
                        .map { it.dictionaryName }
                        .firstOrNull { it.isNotBlank() } ?: ""

                    MergedWordEntry(
                        primaryId = primary.id,
                        primaryExpression = primaryExpression,
                        reading = reading,
                        definitions = allDefinitions,
                        alternativeExpressions = alternatives,
                        frequency = bestFrequency,
                        pitchAccent = pitchAccent,
                        partsOfSpeech = allPartsOfSpeech,
                        dictionaryName = dictionaryName,
                        entryIds = group.map { it.id },
                        exampleSentence = example?.exampleSentence ?: "",
                        exampleSentenceTranslation = example?.exampleSentenceTranslation ?: "",
                        audioFile = audioFile
                    )
                }
                // Preserve order from SQL query (already sorted by relevance + frequency)
        }
    }
}
