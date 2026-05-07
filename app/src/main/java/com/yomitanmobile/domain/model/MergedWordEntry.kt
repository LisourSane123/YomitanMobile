package com.yomitanmobile.domain.model

/**
 * Represents a merged/consolidated search result.
 * Multiple [WordEntry] items representing the same written form are grouped into one.
 */
data class MergedWordEntry(
    val primaryId: Long,
    val primaryExpression: String,
    val reading: String,
    val definitions: List<String>,
    val meaningBlocks: List<MeaningBlock> = emptyList(),
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

    fun definitionText(): String = definitions.mapIndexed { index, definition -> "${index + 1}. $definition" }.joinToString("\n")

    fun definitionTextShort(): String {
        val joined = definitions.mapIndexed { index, definition -> "${index + 1}. $definition" }.joinToString("\n")
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
        meaningBlocks = meaningBlocks,
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
        fun isKanji(c: Char): Boolean {
            val type = Character.UnicodeBlock.of(c)
            return type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    type == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                    type == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        }

        /**
         * Returns true if the string contains at least one kanji character.
         */
        fun containsKanji(s: String): Boolean = s.any { isKanji(it) }

        /**
         * Merge a list of [WordEntry] items into consolidated [MergedWordEntry] items.
         * Groups entries by expression + reading. Within each group:
         * - Prefers the highest-priority dictionary (Jitendex first, then JMdict)
         * - Picks the best expression (prefers kanji forms, then frequency)
         * - Collects all unique alternative expressions
         * - Merges all unique definitions
         * - Takes the best frequency, first non-empty pitch accent, etc.
         */
        fun mergeEntries(entries: List<WordEntry>): List<MergedWordEntry> {
            if (entries.isEmpty()) return emptyList()

            val groups = LinkedHashMap<Pair<String, String>, MutableList<WordEntry>>()
            for (entry in entries) {
                val key = buildMergeKey(entry)
                groups.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return groups.values.map { group ->
                val preferredPriority = group.minOf { dictionaryPriority(it.dictionaryName) }
                val preferredGroup = group.filter { dictionaryPriority(it.dictionaryName) == preferredPriority }

                val sorted = preferredGroup.sortedWith(
                    compareByDescending<WordEntry> { containsKanji(it.expression) }
                        .thenBy { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }
                )

                val primary = sorted.first()
                val primaryExpression = primary.expression
                val reading = primary.reading.ifBlank { primaryExpression }

                val allExpressions = preferredGroup.map { it.expression }
                    .filter { it.isNotBlank() }
                    .distinct()

                val alternatives = allExpressions.filter { it != primaryExpression }

                val allDefinitions = preferredGroup
                    .flatMap { it.definitions }
                    .filter { it.isNotBlank() }
                    .distinct()

                val meaningBlocks = preferredGroup
                    .map { it.meaningBlocks }
                    .firstOrNull { it.isNotEmpty() }
                    ?: emptyList()

                val allPartsOfSpeech = preferredGroup
                    .map { it.partsOfSpeech }
                    .filter { it.isNotBlank() }
                    .distinct()

                val bestFrequency = preferredGroup
                    .map { it.frequency }
                    .filter { it > 0 }
                    .minOrNull() ?: 0

                val pitchAccent = preferredGroup
                    .map { it.pitchAccent }
                    .firstOrNull { it.isNotBlank() } ?: ""

                val example = preferredGroup.firstOrNull { it.exampleSentence.isNotBlank() }

                val audioFile = preferredGroup
                    .map { it.audioFile }
                    .firstOrNull { it.isNotBlank() } ?: ""

                val dictionaryName = preferredGroup
                    .map { it.dictionaryName }
                    .firstOrNull { it.isNotBlank() } ?: ""

                MergedWordEntry(
                    primaryId = primary.id,
                    primaryExpression = primaryExpression,
                    reading = reading,
                    definitions = allDefinitions,
                    meaningBlocks = meaningBlocks,
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
        }

        private fun buildMergeKey(entry: WordEntry): Pair<String, String> {
            val expression = entry.expression.trim()
            val reading = entry.reading.trim()
            return expression.ifBlank { reading } to reading.ifBlank { expression }
        }

        private fun dictionaryPriority(dictionaryName: String): Int {
            val normalized = dictionaryName.lowercase()
            return when {
                normalized.contains("jitendex") -> 0
                normalized.contains("jmdict") -> 1
                else -> 2
            }
        }
    }
}
