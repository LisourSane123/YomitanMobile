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
     * "I already know the commonest N words of the language" — words ranked
     * better than this are dropped, unranked words are unaffected.
     *
     * The frequency-sorted card order means the top of any deck is by
     * definition the words a reader past beginner level already knows; the
     * scanned text cannot tell them apart from new ones, only the user can.
     * 0 disables the cut.
     */
    val assumeKnownTopRank: Int = 0,
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
     * Put the sentence the word appeared in on the FRONT of the card, under
     * the word itself. This is the whole reason for mining from material you
     * watched or read: the card asks you to recall the word in the context you
     * met it in. Turning it on forces the card style's front-context option for
     * this batch only.
     */
    val useSourceSentences: Boolean = true,
    /**
     * Drop the grammatical scaffolding — particles, copulas, conjunctions,
     * auxiliaries, する/いる/ある and friends. They are dictionary entries and
     * they top every frequency list, so without this the first hundred cards of
     * any deck are です and ます. Two sources feed it: a literal stoplist and
     * the dictionary's own part-of-speech tags, the latter being what catches
     * compound grammar (それでも, ということ) a list can never enumerate.
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
    ASSUMED_KNOWN,
    ARCHAIC,
    PROPER_NAME,
    ALREADY_IN_ANKI,
    ALREADY_MINED,
    OVER_LIMIT
}

/**
 * One distinct word the scan found, before any filtering.
 *
 * [earliness] is 1.0 for a word that opens the first file and 0.0 for one that
 * only shows up on the last page — with a whole series loaded in reading order
 * that is exactly "how soon does this word start paying off".
 */
data class ScanToken(
    val baseForm: String,
    val occurrences: Int,
    /** Sentence it was first met in, for the card front. May be empty. */
    val sentence: String = "",
    val earliness: Float = 1f
)

/** A word kept by the scan, with how often the text used it. */
data class ScannedWord(
    val entry: MergedWordEntry,
    val occurrences: Int,
    /** Sentence from the source material, or empty. */
    val sentence: String = "",
    /**
     * Study-order score; see [com.yomitanmobile.domain.usecase.TextScanPlanner]
     * for the weighting. Higher comes first, and the order cards are written in
     * is the order AnkiDroid introduces them in.
     */
    val score: Float = 0f
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
    /** Files that went into this scan, in the order they were read. */
    val sources: List<TextScanSource>,
    /** Distinct dictionary words the tokeniser found. */
    val distinctWordCount: Int,
    /** Total running words (tokens) in the text. */
    val totalTokenCount: Int,
    val selected: List<ScannedWord>,
    val skipped: Map<TextScanSkipReason, Int>,
    /**
     * Running words the user already knows: occurrences of words found in the
     * Anki collection, already mined, grammatical scaffolding, or below the
     * [TextScanFilters.assumeKnownTopRank] the user declared known.
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
    val fileCount: Int get() = sources.size

    /**
     * Share of the running text made up of words the user already knows — the
     * comprehension figure a reader cares about when picking what to read next.
     */
    val knownCoverage: Float
        get() = if (totalTokenCount <= 0) 0f
        else (knownTokenCount.toFloat() / totalTokenCount).coerceIn(0f, 1f)
}
