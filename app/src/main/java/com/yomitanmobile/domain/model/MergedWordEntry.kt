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
    val alternativeExpressions: List<String>,
    val frequency: Int = 0,
    val pitchAccent: String = "",
    val partsOfSpeech: List<String> = emptyList(),
    val dictionaryName: String = "",
    val entryIds: List<Long> = emptyList(),
    val exampleSentence: String = "",
    val exampleSentenceTranslation: String = "",
    val audioFile: String = "",
    // 0 = no JLPT; 1-5 = N1-N5. Populated from jlpt_level DB column.
    val jlptLevel: Int = 0,
    // Full list of (jp, en) example pairs from the dictionary.
    val examples: List<ExamplePair> = emptyList(),
    // Usage hints (e.g. "usually kana", "formal") collected from the grouped
    // entries' WordEntry.usageTags. Rendered as a chip near the JLPT badge.
    val usageTags: List<String> = emptyList(),
    // Cross-references and notes ("see also …", "cf. …", "Note: …") merged
    // from the grouped entries' WordEntry.notes. Rendered as a separate
    // card at the bottom of the detail screen.
    val notes: List<String> = emptyList()
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
        frequency <= 30000 -> "Top 30K"
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
        audioFile = audioFile,
        jlptLevel = jlptLevel,
        examples = examples,
        usageTags = usageTags,
        notes = notes
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
         * - Picks the best expression (prefers kanji forms, then frequency)
         * - Collects all unique alternative expressions
         * - Merges all unique definitions
         * - Takes the best frequency, first non-empty pitch accent, etc.
         */
        fun mergeEntries(entries: List<WordEntry>): List<MergedWordEntry> {
            if (entries.isEmpty()) return emptyList()

            // Use LinkedHashMap to preserve order of first appearance (from SQL query)
            val groups = LinkedHashMap<Pair<String, String>, MutableList<WordEntry>>()
            for (entry in entries) {
                val key = buildMergeKey(entry)
                groups.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return groups.values.map { group ->
                // Sort: prefer entries with kanji and frequency
                val sorted = group.sortedWith(
                    compareByDescending<WordEntry> { containsKanji(it.expression) }
                        .thenBy { if (it.frequency > 0) it.frequency else Int.MAX_VALUE }
                )

                val primary = sorted.first()
                val primaryExpression = primary.expression
                val reading = primary.reading.ifBlank { primaryExpression }

                // Collect all unique expressions
                val allExpressions = group.map { it.expression }
                    .filter { it.isNotBlank() }
                    .distinct()

                // Alternative expressions (excluding the primary)
                val alternatives = allExpressions
                    .filter { it != primaryExpression }

                // Merge definitions (blank-filtered + deduplicated) AND remap
                // every example's definitionIndex onto the merged list in one
                // pass. Doing it together is the whole point: the dedup shifts
                // positions relative to the per-entry lists the examples were
                // indexed against, so they must be translated in lockstep — see
                // mergeDefinitionsAndExamples.
                val (allDefinitions, examples) = mergeDefinitionsAndExamples(group)

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

                // JLPT level: pick the highest number (N5=5 = easiest/earliest level)
                // because the word should be known from the lowest JLPT tier it appears in.
                val jlptLevel = group.maxOfOrNull { it.jlptLevel } ?: 0

                // Union of usage tags across the group, preserving first-seen
                // order so the chip reads consistently across imports.
                val mergedUsageTags = LinkedHashSet<String>().apply {
                    group.forEach { addAll(it.usageTags) }
                }.toList()

                // Union of cross-references / notes across the group, same
                // first-seen preservation as usageTags.
                val mergedNotes = LinkedHashSet<String>().apply {
                    group.forEach { addAll(it.notes) }
                }.toList()

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
                    audioFile = audioFile,
                    jlptLevel = jlptLevel,
                    examples = examples,
                    usageTags = mergedUsageTags,
                    notes = mergedNotes
                )
            }
                // Preserve order from SQL query (already sorted by relevance + frequency)
        }

        /**
         * Merge a group's glosses into one blank-filtered, deduplicated list
         * and return the example list whose per-sense
         * [ExamplePair.definitionIndex] has been remapped onto the positions in
         * that merged list.
         *
         * Why this has to be one function: [mergeEntries] deduplicates
         * definitions, which shifts their positions relative to the per-entry
         * lists that each [ExamplePair] was originally indexed against. If the
         * indices aren't translated in the same pass, an example renders under
         * the wrong numbered meaning (or points past the end of the list). The
         * consumers — the detail screen and the Anki export — can then simply
         * trust `definitionIndex` and group by it, with no compensation logic
         * of their own.
         *
         * Examples come from a single source entry (the first in the group with
         * a non-empty list), so only that entry's local indices need mapping.
         * An index that pointed at a gloss which got blank-filtered away resets
         * to -1 (unattached) so the example still shows rather than latching
         * onto an unrelated meaning.
         */
        internal fun mergeDefinitionsAndExamples(
            group: List<WordEntry>
        ): Pair<List<String>, List<ExamplePair>> {
            // Insertion-ordered so value == position in the final keys list.
            val defToMergedIndex = LinkedHashMap<String, Int>()
            val exampleEntry = group.firstOrNull { it.examples.isNotEmpty() }
            val localToMerged = HashMap<Int, Int>()

            for (entry in group) {
                entry.definitions.forEachIndexed { localIndex, def ->
                    if (def.isBlank()) return@forEachIndexed
                    val mergedIndex = defToMergedIndex.getOrPut(def) { defToMergedIndex.size }
                    if (entry === exampleEntry) {
                        localToMerged.putIfAbsent(localIndex, mergedIndex)
                    }
                }
            }

            val definitions = defToMergedIndex.keys.toList()
            val examples = exampleEntry?.examples.orEmpty().map { ex ->
                if (ex.definitionIndex < 0) ex
                else {
                    val remapped = localToMerged[ex.definitionIndex] ?: -1
                    if (remapped == ex.definitionIndex) ex
                    else ex.copy(definitionIndex = remapped)
                }
            }
            return definitions to examples
        }

        private fun buildMergeKey(entry: WordEntry): Pair<String, String> {
            val expressionKey = entry.expression.ifBlank { entry.reading }
            val readingKey = entry.reading.ifBlank { entry.expression }
            return expressionKey to readingKey
        }
    }
}
