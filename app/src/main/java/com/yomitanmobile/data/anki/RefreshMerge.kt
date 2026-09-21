package com.yomitanmobile.data.anki

/**
 * What a refresh of an EXISTING note writes, field by field: today's rebuild
 * where it may, the note's own value where only the note knows the answer.
 *
 * Shared by the phone ([AnkiNoteRefresher]) and the desktop tool (KindleSync's
 * refresh), because the rule is the whole safety of a refresh and two copies of
 * it drift.
 *
 * "A field the rebuild leaves empty keeps the old value" was the only rule for
 * a long time, and it protected less than it claimed to. The rebuild does not
 * leave the front-context slot empty: with the front sentence switched on it
 * fills it with the first example the DICTIONARY has, so the sentence the word
 * was mined from — out of a book the app no longer has — was replaced by a
 * stranger's example the moment the card was refreshed. The front had the same
 * problem in another form: the rebuild writes the dictionary's headword in a
 * freshly drawn random font, so a card fronted with the spelling its book used
 * (えっち, not JMdict's Ｈ) came back as a word the reader had never seen there.
 */
object RefreshMerge {

    /**
     * Fields that belong to the NOTE, not to the dictionary. A non-empty value
     * is kept whatever the rebuild says; an empty one is filled.
     *
     *  • Front — the spelling the card is recognised by, and its font.
     *  • FrontContext — the sentence the word was met in.
     *  • Summary — an AI summary: an API call per card, never regenerated here.
     */
    val OWNED_BY_NOTE = setOf("Front", "FrontContext", "Summary")

    /**
     * The frequency field holds a plain rank ("4821") and nothing else: a tier
     * label ("★★★ Top 1K") is what older versions wrote, it sorts last in every
     * reorder add-on, and it is never correct any more. So a label is dropped
     * even when the rebuild has no number to put in its place.
     */
    const val FREQUENCY = "Frequency"

    /**
     * The new value of every field of [current], by name.
     *
     * Only names [current] has are returned — a note type without Summary never
     * receives one — and in [current]'s own order, which is the order a
     * provider or AnkiConnect write expects.
     */
    fun merge(current: Map<String, String>, rebuilt: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>(current.size)
        for ((name, old) in current) {
            val fresh = rebuilt[name].orEmpty()
            out[name] = when {
                name in OWNED_BY_NOTE -> if (old.isNotBlank()) old else fresh
                hasUserWriting(old) -> old
                name == FREQUENCY -> when {
                    fresh.isNotBlank() -> fresh
                    isPlainRank(old) -> old
                    else -> ""
                }
                fresh.isNotBlank() -> fresh
                else -> old
            }
        }
        return out
    }

    /**
     * Whether a field carries something the user wrote into it by hand.
     *
     * The app writes glosses in English (the dictionaries are English) and its
     * own Polish only in two labelled places — the part-of-speech line and the
     * usage tags ("zwykle kaną"). Polish letters anywhere else were typed by
     * the user: "pupil (of the eye) źrenica", a whole paragraph under 飛び交う.
     * Such a field is kept as it is, even though the rest of it is stale —
     * a note the user wrote cannot be rebuilt, a gloss can.
     */
    fun hasUserWriting(value: String): Boolean {
        if (value.isBlank()) return false
        val outsideAppLabels = value
            .replace(APP_LABELS, " ")
            .replace(RUBY_READING, " ")
            .replace(TAG, " ")
        return outsideAppLabels.any { it in POLISH_LETTERS }
    }

    private val APP_LABELS = Regex("""<div class="(pos-line|usage-tags)">.*?</div>""", RegexOption.DOT_MATCHES_ALL)
    private val RUBY_READING = Regex("""<rt[^>]*>.*?</rt>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    private const val POLISH_LETTERS = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ"

    /** "4821" yes; "★★★ Top 1K", "" and "4821 (JPDB)" no. */
    fun isPlainRank(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && trimmed.all { it.isDigit() }
    }
}
