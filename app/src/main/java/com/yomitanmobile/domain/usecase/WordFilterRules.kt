package com.yomitanmobile.domain.usecase

import com.yomitanmobile.domain.model.MergedWordEntry

/**
 * "Is this word worth a card at all?" — the tag-level rules shared by every
 * bulk generator ([JlptDeckPlanner] and [TextScanPlanner]).
 *
 * They live here rather than in one of the planners because both answer the
 * same question about the same data, and a divergence would show up as a deck
 * full of surnames in one screen and not the other.
 */
object WordFilterRules {

    /**
     * Sense-level usage tags (WordEntry.usageTags carries the expanded English
     * form) and raw JMdict tags that mark a word as not-worth-learning.
     */
    private val ARCHAIC_TAGS = setOf(
        "arch", "archaic", "obs", "obsolete", "obsc", "obscure",
        "rare", "rarely-used", "dated", "dated term", "ok", "oik"
    )

    /** JMnedict / name-dictionary part-of-speech tags. */
    private val NAME_TAGS = setOf(
        "surname", "place", "unclass", "company", "product", "work",
        "masc", "fem", "given", "person", "organization", "station",
        "creat", "char", "dei", "doc", "ev", "fict", "group", "leg",
        "myth", "obj", "serv", "relig", "oth"
    )

    /** Dictionary names that only ever contain proper names. */
    private val NAME_DICTIONARIES = listOf("jmnedict", "enamdict", "names")

    /**
     * JMdict part-of-speech codes that mark grammatical scaffolding rather than
     * vocabulary: particles, conjunctions, the copula, auxiliaries,
     * interjections, pronouns and the この/その determiners.
     *
     * This is what a hand-written word list cannot do. Longest-match
     * segmentation happily produces compounds — それでも, どころか, にとって,
     * なんて — that are single dictionary entries and therefore
     * never appear in a literal stoplist, but every one of them carries a
     * `conj` / `exp,aux` / `prt` tag from JMdict.
     */
    private val FUNCTION_TAGS = setOf(
        "prt", "conj", "cop", "cop-da", "aux", "aux-v", "aux-adj",
        "int", "pn", "adj-pn", "cnj"
    )

    /**
     * Tags that decide nothing on their own.
     *
     * Inflection paradigms say how a word conjugates, not what it is. They ride
     * along in the same string (Yomitan's `rules` field is merged into
     * `partsOfSpeech`), so they must not be allowed to veto the
     * all-function-tags test below — たい is `aux-adj, adj-i` and is still pure
     * grammar.
     *
     * `exp` is neutral for the same reason from the other direction: it says
     * "this is a phrase", not what kind of phrase. Alone it must not condemn a
     * word (お疲れ様 is `exp` and is worth a card); next to a `prt` or `conj` it
     * must not save one (ということ is `exp, prt`).
     */
    private val NEUTRAL_TAGS = setOf(
        "exp",
        "v1", "v1-s", "v5u", "v5u-s", "v5k", "v5k-s", "v5g", "v5s", "v5t",
        "v5n", "v5b", "v5m", "v5r", "v5r-i", "v5aru", "v4r", "vk", "vs",
        "vs-i", "vs-s", "vs-c", "vz", "vn", "vr", "vt", "vi",
        "adj-i", "adj-ix", "adj-na", "adj-no", "adj-t", "adj-f", "uk"
    )

    /**
     * True when every tag that says *what the word is* marks it as grammar.
     *
     * "All, not any" on purpose, exactly like [isProperName]: 自分 is `pn` in
     * one sense and `n` ("oneself") in another, and a merged entry carries
     * both — so it stays. A word whose only content tag is `conj` (それでも) or
     * `prt` has no vocabulary sense to keep it.
     */
    fun isFunctionWord(entry: MergedWordEntry): Boolean {
        val content = entry.posTokens().filterNot { it in NEUTRAL_TAGS }
        return content.isNotEmpty() && content.all { it in FUNCTION_TAGS }
    }

    fun isArchaic(entry: MergedWordEntry): Boolean =
        entry.usageTags.any { it.normalizeTag() in ARCHAIC_TAGS } ||
            entry.posTokens().any { it in ARCHAIC_TAGS }

    fun isProperName(entry: MergedWordEntry): Boolean {
        // Name dictionaries tag EVERY sense; a normal word that merely also
        // exists as a surname keeps its regular part-of-speech tags too, so
        // only an all-name tag set counts as a proper name.
        val tags = entry.posTokens()
        if (tags.isNotEmpty()) return tags.all { it in NAME_TAGS }
        // No usable tags: fall back to where the entry came from. Only decides
        // the untagged case, because MergedWordEntry keeps a single dictionary
        // name for the whole group — an ordinary word that JMnedict also lists
        // as a surname must not be dropped just because that row was grouped in.
        val fromDictionary = entry.dictionaryName.lowercase()
        return NAME_DICTIONARIES.any { fromDictionary.contains(it) }
    }

    /**
     * Individual part-of-speech tags.
     *
     * [MergedWordEntry.partsOfSpeech] is NOT a token list: each element is one
     * source entry's whole tag string as the parser joined it ("n, v5r, uk").
     * Matching those strings against a token set never fires, which is why the
     * archaic and proper-name filters silently passed everything through
     * before. Split on the separators the parser uses (comma, semicolon and
     * whitespace) and match token by token.
     */
    fun MergedWordEntry.posTokens(): List<String> =
        partsOfSpeech.flatMap { it.split(',', ';', ' ', '\t', '\n') }
            .map { it.normalizeTag() }
            .filter { it.isNotBlank() }

    private fun String.normalizeTag(): String = trim().lowercase()
}
