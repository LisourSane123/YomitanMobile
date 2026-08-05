package com.yomitanmobile.domain.model

/**
 * Configuration for the bulk JLPT deck generator — "give me every N4 word as
 * a card" without mining them one by one.
 *
 * Defaults are tuned for a deck a human would actually study: common words
 * only, no archaisms, nothing that already exists in the collection.
 */
data class JlptDeckFilters(
    /**
     * Drop words whose frequency rank is worse than this (higher rank number
     * = rarer). 0 disables the cut. 30 000 keeps roughly "words a native
     * reader meets in normal text" while dropping the long tail.
     */
    val maxFrequencyRank: Int = 30_000,
    /**
     * Keep words that carry no frequency data at all. Without a frequency
     * dictionary installed EVERY word is unranked, so dropping them by
     * default would silently produce an empty deck.
     */
    val includeUnranked: Boolean = true,
    /** Drop entries tagged archaic / obsolete / rare / obscure / dated. */
    val skipArchaic: Boolean = true,
    /** Drop proper-name entries (JMnedict: surnames, places, companies…). */
    val skipProperNames: Boolean = true,
    /** Drop words already present in the AnkiDroid collection. */
    val skipAlreadyInAnki: Boolean = true,
    /** Drop words this app already exported (any deck). */
    val skipAlreadyMined: Boolean = true,
    /** Hard cap on generated cards; 0 = no cap. */
    val maxWords: Int = 0,
    /**
     * Synthesise TTS audio per card. Off by default: it costs roughly a
     * second per word, which turns a 1 000-card deck into a 20-minute job.
     */
    val generateAudio: Boolean = false
)

/** Why a candidate word did not make it into the generated deck. */
enum class JlptSkipReason {
    NO_DEFINITION,
    TOO_RARE,
    UNRANKED,
    ARCHAIC,
    PROPER_NAME,
    ALREADY_IN_ANKI,
    ALREADY_MINED,
    OVER_LIMIT
}

/**
 * Outcome of the dry run: what would be created, and what fell out where.
 * The UI shows this before anything is written to AnkiDroid.
 */
data class JlptDeckPlan(
    val level: Int,
    val candidateCount: Int,
    val selected: List<MergedWordEntry>,
    val skipped: Map<JlptSkipReason, Int>,
    /** True when the Anki collection could not be read for the dup check. */
    val ankiScanUnavailable: Boolean = false,
    /** Notes found in the collection while scanning (0 when not scanned). */
    val scannedNoteCount: Int = 0
) {
    val selectedCount: Int get() = selected.size
    val skippedCount: Int get() = skipped.values.sum()
}

/** Progress of the write phase, surfaced to the UI. */
data class JlptDeckProgress(
    val done: Int,
    val total: Int,
    val currentWord: String = ""
)

/** Result of the write phase. */
data class JlptDeckResult(
    val deckName: String,
    val added: Int,
    val failed: Int
)
