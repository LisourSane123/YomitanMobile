package com.yomitanmobile.domain.model

/**
 * Bulk deck generation from a text the user actually read or watched:
 * subtitles (SRT/ASS/VTT) or a book (EPUB). The file is tokenised against the
 * installed dictionaries, everything already known is dropped, and what is
 * left becomes cards — the same cards the detail screen exports.
 */
data class TextScanFilters(
    /**
     * Frequency band cards are made for. TOP_10K means "only words ranked
     * 10 000 or better in the installed frequency lists"; [FrequencyTier.ALL]
     * disables the cut.
     */
    val tier: FrequencyTier = FrequencyTier.TOP_20K,
    /**
     * Keep words with no frequency data. Without a frequency dictionary
     * installed EVERY word is unranked, so dropping them by default would
     * turn any tier into an empty deck.
     */
    val includeUnranked: Boolean = true,
    /**
     * Minimum occurrences in the text. 1 keeps everything; 2+ is the useful
     * setting for a novel, where a word seen once is rarely worth a card.
     */
    val minOccurrences: Int = 1,
    /** Drop words already present in the AnkiDroid collection (stored scan). */
    val skipAlreadyInAnki: Boolean = true,
    /** Drop words this app already exported (any deck). */
    val skipAlreadyMined: Boolean = true,
    /** Drop entries tagged archaic / obsolete / rare / obscure / dated. */
    val skipArchaic: Boolean = true,
    /** Drop proper-name entries (JMnedict: surnames, places, companies…). */
    val skipProperNames: Boolean = true,
    /**
     * Drop the grammatical scaffolding — particles, copulas, する/いる/ある and
     * friends. They are dictionary entries and they top every frequency list,
     * so without this the first hundred cards of any deck are です and ます.
     */
    val skipFunctionWords: Boolean = true,
    /** Hard cap on generated cards; 0 = no cap. */
    val maxWords: Int = 0,
    /** Synthesise TTS audio per card (slow — roughly a second per word). */
    val generateAudio: Boolean = false
)

/** Why a word found in the text did not become a card. */
enum class TextScanSkipReason {
    NOT_IN_DICTIONARY,
    TOO_FEW_OCCURRENCES,
    FUNCTION_WORD,
    NO_DEFINITION,
    TOO_RARE,
    UNRANKED,
    ARCHAIC,
    PROPER_NAME,
    ALREADY_IN_ANKI,
    ALREADY_MINED,
    OVER_LIMIT
}

/** A word kept by the scan, with how often the text used it. */
data class ScannedWord(
    val entry: MergedWordEntry,
    val occurrences: Int
)

/** What reading the file itself produced, before any word filtering. */
data class TextScanSource(
    val fileName: String,
    val formatLabel: String,
    val charsetName: String,
    /** Characters of Japanese text extracted. */
    val characterCount: Int,
    /** EPUB chapters or subtitle cues read; 0 when not applicable. */
    val partCount: Int
)

/**
 * Outcome of the dry run: what would be created and what fell out where.
 * `selected.size + skipped.values.sum() == distinctWordCount`, so the numbers
 * the UI prints add up.
 */
data class TextScanPlan(
    val source: TextScanSource,
    /** Distinct dictionary words the tokeniser found. */
    val distinctWordCount: Int,
    /** Total running words (tokens) in the text. */
    val totalTokenCount: Int,
    val selected: List<ScannedWord>,
    val skipped: Map<TextScanSkipReason, Int>,
    /**
     * Running words the user demonstrably already knows: occurrences of words
     * found in the Anki collection, already mined, or grammatical scaffolding.
     * Deliberately NOT "everything that got filtered out" — a word dropped for
     * being too rare is unknown, and counting it as known would inflate the
     * comprehension figure the user reads.
     */
    val knownTokenCount: Int = 0,
    /** True when the Anki collection has never been scanned. */
    val ankiScanUnavailable: Boolean = false
) {
    val selectedCount: Int get() = selected.size
    val skippedCount: Int get() = skipped.values.sum()

    /**
     * Share of the running text made up of words the user already knows — the
     * comprehension figure a reader cares about when picking what to read next.
     */
    val knownCoverage: Float
        get() = if (totalTokenCount <= 0) 0f
        else (knownTokenCount.toFloat() / totalTokenCount).coerceIn(0f, 1f)
}
