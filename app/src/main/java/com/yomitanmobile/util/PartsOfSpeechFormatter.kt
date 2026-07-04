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

    // Polish counterparts to POS_LABELS, keyed by the same JMDict codes. Used
    // when the app runs in Polish. Missing keys fall back to the English label,
    // then to the raw code, so an unmapped tag never disappears.
    private val POS_LABELS_PL = mapOf(
        // Verbs
        "v1" to "czasownik ichidan",
        "v1-s" to "czasownik ichidan (specjalny)",
        "v5u" to "czasownik godan (う)",
        "v5k" to "czasownik godan (く)",
        "v5g" to "czasownik godan (ぐ)",
        "v5s" to "czasownik godan (す)",
        "v5t" to "czasownik godan (つ)",
        "v5n" to "czasownik godan (ぬ)",
        "v5b" to "czasownik godan (ぶ)",
        "v5m" to "czasownik godan (む)",
        "v5r" to "czasownik godan (る)",
        "v5r-i" to "czasownik godan (る, nieregularny)",
        "v5aru" to "czasownik godan (-aru)",
        "v5k-s" to "czasownik godan (iku/yuku)",
        "v5u-s" to "czasownik godan (う, specjalny)",
        "v5uru" to "czasownik godan (-uru)",
        "v2a-s" to "czasownik nidan (specjalny)",
        "vk" to "czasownik kuru",
        "vs" to "czasownik suru",
        "vs-i" to "czasownik suru (nieregularny)",
        "vs-s" to "czasownik suru (specjalny)",
        "vs-c" to "czasownik suru (złożony)",
        "vt" to "czasownik przechodni",
        "vi" to "czasownik nieprzechodni",
        "vn" to "nieregularny czasownik nu",
        "vr" to "nieregularny czasownik ru",
        "vz" to "czasownik ichidan zuru",
        "aux" to "wyraz posiłkowy",
        "aux-v" to "czasownik posiłkowy",
        "aux-adj" to "przymiotnik posiłkowy",
        // Adjectives
        "adj-i" to "przymiotnik (i)",
        "adj-ix" to "przymiotnik (i) (yoi/ii)",
        "adj-na" to "przymiotnik (na)",
        "adj-no" to "określnik z の",
        "adj-pn" to "przydawka (rentaishi)",
        "adj-t" to "przymiotnik taru",
        "adj-f" to "określnik rzecz./czas.",
        "adj-ku" to "przymiotnik ku (archaiczny)",
        "adj-shiku" to "przymiotnik shiku (archaiczny)",
        "adj-nari" to "przymiotnik nari (archaiczny)",
        // Nouns
        "n" to "rzeczownik",
        "n-suf" to "przyrostek rzeczownikowy",
        "n-pref" to "przedrostek rzeczownikowy",
        "n-t" to "rzeczownik czasowy",
        "n-adv" to "rzeczownik przysłówkowy",
        "pn" to "zaimek",
        "num" to "liczebnik",
        // Adverbs / particles / etc.
        "adv" to "przysłówek",
        "adv-to" to "przysłówek z と",
        "prt" to "partykuła",
        "conj" to "spójnik",
        "int" to "wykrzyknik",
        "exp" to "wyrażenie",
        "pref" to "przedrostek",
        "suf" to "przyrostek",
        "ctr" to "licznik (klasyfikator)",
        "cop" to "łącznik (copula)",
        // Usage / register
        "uk" to "zwykle pisane kaną",
        "obs" to "przestarzały",
        "arch" to "archaizm",
        "rare" to "rzadki",
        "sl" to "slang",
        "col" to "kolokwializm",
        "fam" to "poufały",
        "hon" to "grzecznościowy",
        "hum" to "skromny (kenjōgo)",
        "pol" to "grzeczny",
        "vulg" to "wulgarny",
        "derog" to "pejoratywny",
        "joc" to "żartobliwy",
        "abbr" to "skrót",
        "yoji" to "yojijukugo",
        "id" to "idiomatyczny",
        "proverb" to "przysłowie",
        "quote" to "cytat",
        "on-mim" to "onomatopeja",
        // Domain
        "comp" to "informatyka",
        "math" to "matematyka",
        "med" to "medycyna",
        "law" to "prawo",
        "Buddh" to "buddyzm",
        "Shinto" to "shintoizm",
        "physics" to "fizyka",
        "chem" to "chemia",
        "biol" to "biologia",
        "bus" to "biznes",
        "econ" to "ekonomia",
        "food" to "kulinaria",
        "sports" to "sport",
        "music" to "muzyka"
    )

    // Polish translations for the already-normalised usage-tag labels that end
    // up in WordEntry.usageTags (the English short forms produced by
    // UsageTagExtractor / SHORT_USAGE_LABELS). Keyed by the English label so we
    // can translate at display time without re-importing.
    private val USAGE_LABELS_PL = mapOf(
        "usually kana" to "zwykle kaną",
        "usually kanji" to "zwykle kanji",
        "obsolete" to "przestarzały",
        "archaic" to "archaizm",
        "rare" to "rzadki",
        "slang" to "slang",
        "colloq." to "kolokwializm",
        "familiar" to "poufały",
        "honorific" to "grzecznościowy",
        "humble" to "skromny",
        "polite" to "grzeczny",
        "vulgar" to "wulgarny",
        "euph." to "eufemizm",
        "derog." to "pejoratywny",
        // Dialekty (regionalizmy) — klucze zgodne z DIALECT_LABELS.
        "Hokkaido dial." to "dialekt Hokkaido",
        "Kansai dial." to "dialekt Kansai",
        "Kanto dial." to "dialekt Kanto",
        "Kyoto dial." to "dialekt Kioto",
        "Kyushu dial." to "dialekt Kiusiu",
        "Nagano dial." to "dialekt Nagano",
        "Osaka dial." to "dialekt Osaka",
        "Ryukyu dial." to "dialekt Riukiu",
        "Tohoku dial." to "dialekt Tohoku",
        "Tosa dial." to "dialekt Tosa",
        "Tsugaru dial." to "dialekt Tsugaru",
        "joc." to "żartobliwy",
        "abbr." to "skrót",
        "yoji." to "yojijukugo",
        "idiom" to "idiom",
        "proverb" to "przysłowie",
        "quote" to "cytat",
        "onomat." to "onomatopeja",
        "mimetic" to "mimetyczny",
        "formal" to "formalny",
        "male" to "męski",
        "fem." to "żeński",
        "children" to "dziecięcy",
        "X-rated" to "dla dorosłych",
        "comp." to "informatyka",
        "math" to "matematyka",
        "med." to "medycyna",
        "law" to "prawo",
        "Buddh." to "buddyzm",
        "Shinto" to "shintoizm",
        "phys." to "fizyka",
        "chem." to "chemia",
        "biol." to "biologia",
        "bus." to "biznes",
        "econ." to "ekonomia",
        "food" to "kulinaria",
        "sports" to "sport",
        "music" to "muzyka"
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
        "euph" to "euph.",
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

    // JMdict dialect codes (regional Japanese) as Jitendex emits them under
    // <span data-content="dialect-info" data.code="…">. Rendered as an English
    // short label that doubles as the key for the Polish translation and for
    // UsageTagExtractor's recognised-tag set.
    private val DIALECT_LABELS = mapOf(
        "hob" to "Hokkaido dial.",
        "ksb" to "Kansai dial.",
        "ktb" to "Kanto dial.",
        "kyb" to "Kyoto dial.",
        "kyu" to "Kyushu dial.",
        "nab" to "Nagano dial.",
        "osb" to "Osaka dial.",
        "rkb" to "Ryukyu dial.",
        "thb" to "Tohoku dial.",
        "tsb" to "Tosa dial.",
        "tsug" to "Tsugaru dial."
    )

    /** All dialect short labels — used to widen the recognised-tag set. */
    val dialectLabels: Collection<String> get() = DIALECT_LABELS.values

    /**
     * Short label for a JMdict dialect code (e.g. "ksb" → "Kansai dial."),
     * or null when the code isn't a known dialect.
     */
    fun dialectLabelForCode(code: String): String? = DIALECT_LABELS[code]

    /**
     * Tokenize a raw JMDict tag string and return a comma-separated readable label list.
     * Empty result means there is nothing useful to display.
     *
     * When [english] is false, Polish labels ([POS_LABELS_PL]) are preferred,
     * falling back to the English label and finally the raw code so nothing is
     * dropped. Kept as the default English-only overload for tests / callers
     * that don't have a locale handy.
     */
    fun format(rawTags: String, english: Boolean = true): String {
        if (rawTags.isBlank()) return ""
        val seen = LinkedHashSet<String>()
        for (token in SPLIT_REGEX.split(rawTags)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed in FREQUENCY_TAGS) continue
            if (trimmed in NON_POS_NOISE) continue
            if (JLPT_REGEX.matches(trimmed)) continue
            if (NF_REGEX.matches(trimmed)) continue
            val label = if (english) {
                POS_LABELS[trimmed]
            } else {
                POS_LABELS_PL[trimmed] ?: POS_LABELS[trimmed]
            }
            seen.add(label ?: trimmed)
        }
        return seen.joinToString(", ")
    }

    /**
     * Translate a single already-normalised usage-tag label (the English short
     * form stored in [com.yomitanmobile.domain.model.WordEntry.usageTags]) to
     * Polish when [english] is false. Unknown labels pass through unchanged.
     */
    fun localizeUsageTag(label: String, english: Boolean): String {
        if (english) return label
        return USAGE_LABELS_PL[label.trim()] ?: label
    }
}
