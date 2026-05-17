package com.yomitanmobile.util

/**
 * Converts raw JMDict tag tokens (v1, vt, adj-i, …) into human-readable labels.
 * Frequency markers (ichi1/news1/nfXX) and JLPT tags are filtered out — they are
 * surfaced separately in the UI.
 */
object PartsOfSpeechFormatter {

    private val POS_LABELS = mapOf(
        // Verbs
        "v1" to "ichidan verb",
        "v1-s" to "ichidan verb (special)",
        "v5u" to "godan verb (う)",
        "v5k" to "godan verb (く)",
        "v5g" to "godan verb (ぐ)",
        "v5s" to "godan verb (す)",
        "v5t" to "godan verb (つ)",
        "v5n" to "godan verb (ぬ)",
        "v5b" to "godan verb (ぶ)",
        "v5m" to "godan verb (む)",
        "v5r" to "godan verb (る)",
        "v5r-i" to "godan verb (る, irregular)",
        "v5aru" to "godan verb (-aru)",
        "v5k-s" to "godan verb (iku/yuku)",
        "v5u-s" to "godan verb (う, special)",
        "v5uru" to "godan verb (-uru)",
        "v2a-s" to "nidan verb (special)",
        "vk" to "kuru verb",
        "vs" to "suru verb",
        "vs-i" to "suru verb (irregular)",
        "vs-s" to "suru verb (special)",
        "vs-c" to "suru verb (compound)",
        "vt" to "transitive verb",
        "vi" to "intransitive verb",
        "vn" to "irregular nu verb",
        "vr" to "irregular ru verb",
        "vz" to "ichidan zuru verb",
        "aux" to "auxiliary",
        "aux-v" to "auxiliary verb",
        "aux-adj" to "auxiliary adjective",
        // Adjectives
        "adj-i" to "i-adjective",
        "adj-ix" to "i-adjective (yoi/ii)",
        "adj-na" to "na-adjective",
        "adj-no" to "noun-modifying with の",
        "adj-pn" to "pre-noun adjective",
        "adj-t" to "taru adjective",
        "adj-f" to "noun/verb modifier",
        "adj-ku" to "ku adjective (archaic)",
        "adj-shiku" to "shiku adjective (archaic)",
        "adj-nari" to "nari adjective (archaic)",
        // Nouns
        "n" to "noun",
        "n-suf" to "noun suffix",
        "n-pref" to "noun prefix",
        "n-t" to "temporal noun",
        "n-adv" to "adverbial noun",
        "pn" to "pronoun",
        "num" to "numeric",
        // Adverbs / particles / etc.
        "adv" to "adverb",
        "adv-to" to "adverb taking と",
        "prt" to "particle",
        "conj" to "conjunction",
        "int" to "interjection",
        "exp" to "expression",
        "pref" to "prefix",
        "suf" to "suffix",
        "ctr" to "counter",
        "cop" to "copula",
        // Usage / register
        "uk" to "usually written in kana",
        "obs" to "obsolete",
        "arch" to "archaic",
        "rare" to "rare",
        "sl" to "slang",
        "col" to "colloquial",
        "fam" to "familiar",
        "hon" to "honorific",
        "hum" to "humble",
        "pol" to "polite",
        "vulg" to "vulgar",
        "derog" to "derogatory",
        "joc" to "jocular",
        "abbr" to "abbreviation",
        "yoji" to "yojijukugo",
        "id" to "idiomatic",
        "proverb" to "proverb",
        "quote" to "quotation",
        "on-mim" to "onomatopoeia",
        // Domain
        "comp" to "computing",
        "math" to "math",
        "med" to "medical",
        "law" to "law",
        "Buddh" to "Buddhism",
        "Shinto" to "Shinto",
        "physics" to "physics",
        "chem" to "chemistry",
        "biol" to "biology",
        "bus" to "business",
        "econ" to "economics",
        "food" to "food",
        "sports" to "sports",
        "music" to "music"
    )

    private val FREQUENCY_TAGS = setOf(
        "ichi1", "ichi2",
        "news1", "news2",
        "spec1", "spec2",
        "gai1", "gai2"
    )

    // Visual / metadata markers that show up in Yomitan termTags but are not
    // real grammar info. "P" = priority form; the unicode glyphs come from
    // Jitendex's badge tags. Filtering them keeps the POS chip readable.
    private val NON_POS_NOISE = setOf(
        "P", "★", "priority", "form"
    )

    private val JLPT_REGEX = Regex("""(?i)^jlpt[\s_-]?n?[\s_-]?[1-5]$""")
    private val NF_REGEX = Regex("""(?i)^nf\d+$""")
    private val SPLIT_REGEX = Regex("""[,;\s]+""")

    /**
     * Human-readable label for a single JMDict tag code, or null when the
     * code isn't in the known POS / usage / domain map. Used by the Jitendex
     * parser to resolve usage hints when the structured-content `title`
     * attribute is missing.
     */
    fun labelForCode(code: String): String? = POS_LABELS[code]

    // Compact labels for the in-gloss usage-hint pill. Jitendex `title`s like
    // "word usually written using kana alone" would eat half the card; these
    // short forms keep the meaning legible without dominating the line.
    private val SHORT_USAGE_LABELS = mapOf(
        "uk" to "usually kana",
        "uK" to "usually kanji",
        "obs" to "obsolete",
        "arch" to "archaic",
        "rare" to "rare",
        "sl" to "slang",
        "col" to "colloq.",
        "fam" to "familiar",
        "hon" to "honorific",
        "hum" to "humble",
        "pol" to "polite",
        "vulg" to "vulgar",
        "derog" to "derog.",
        "joc" to "joc.",
        "abbr" to "abbr.",
        "yoji" to "yoji.",
        "id" to "idiom",
        "proverb" to "proverb",
        "quote" to "quote",
        "on-mim" to "onomat.",
        "form" to "formal",
        "male" to "male",
        "fem" to "fem.",
        "chn" to "children",
        "X" to "X-rated",
        "comp" to "comp.",
        "math" to "math",
        "med" to "med.",
        "law" to "law",
        "Buddh" to "Buddh.",
        "Shinto" to "Shinto",
        "physics" to "phys.",
        "chem" to "chem.",
        "biol" to "biol.",
        "bus" to "bus.",
        "econ" to "econ.",
        "food" to "food",
        "sports" to "sports",
        "music" to "music"
    )

    /**
     * Compact label for a usage / domain tag code (e.g. "uk" → "usually kana"),
     * or null when the code isn't known. Prefer this over the full Jitendex
     * `title` attribute when surfacing tags inline with a gloss.
     */
    fun shortUsageLabelForCode(code: String): String? = SHORT_USAGE_LABELS[code]

    /**
     * Tokenize a raw JMDict tag string and return a comma-separated readable label list.
     * Empty result means there is nothing useful to display.
     */
    fun format(rawTags: String): String {
        if (rawTags.isBlank()) return ""
        val seen = LinkedHashSet<String>()
        for (token in SPLIT_REGEX.split(rawTags)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed in FREQUENCY_TAGS) continue
            if (trimmed in NON_POS_NOISE) continue
            if (JLPT_REGEX.matches(trimmed)) continue
            if (NF_REGEX.matches(trimmed)) continue
            seen.add(POS_LABELS[trimmed] ?: trimmed)
        }
        return seen.joinToString(", ")
    }
}
