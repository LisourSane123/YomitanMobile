package com.yomitanmobile.util

/**
 * The language a dictionary tag is shown in. Japanese is not an interface
 * language here: it is what a card whose meaning is written in Japanese gets,
 * so the label and the definition beside it speak one language.
 */
enum class LabelLanguage {
    ENGLISH, POLISH, JAPANESE;

    companion object {
        fun of(english: Boolean): LabelLanguage = if (english) ENGLISH else POLISH
    }
}

/**
 * Converts raw JMDict tag tokens (v1, vt, adj-i, …) into human-readable labels.
 * Frequency markers (ichi1/news1/nfXX) and JLPT tags are filtered out — they are
 * surfaced separately in the UI.
 */
object PartsOfSpeechFormatter {

    private val POS_LABELS = mapOf(
        // Wiktionary (kty-*) part-of-speech codes, used by the English and
        // Spanish dictionaries. They are coarser than JMdict's — a verb is
        // just "v", with no conjugation class — and they do not overlap with
        // it, so both sets live in one map. Without these, cards and the
        // detail screen printed the raw code: "v", "adj", "prep".
        "v" to "verb",
        "adj" to "adjective",
        "prep" to "preposition",
        "pron" to "pronoun",
        "intj" to "interjection",
        "name" to "proper noun",
        "phrase" to "phrase",
        "prefix" to "prefix",
        "suffix" to "suffix",
        "infix" to "infix",
        "postp" to "postposition",
        "det" to "determiner",
        "article" to "article",
        "particle" to "particle",
        "contraction" to "contraction",
        "abbrev" to "abbreviation",
        "character" to "character",
        "punct" to "punctuation",
        // kaikki's placeholder for an entry whose part of speech it could not
        // determine. Printing "unknown" on a card is noise, so it collapses to
        // nothing via NON_POS_NOISE below.

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
        // Wiktionary (kty-*) codes — see POS_LABELS.
        "v" to "czasownik",
        "adj" to "przymiotnik",
        "prep" to "przyimek",
        "pron" to "zaimek",
        "intj" to "wykrzyknik",
        "name" to "nazwa własna",
        "phrase" to "wyrażenie",
        "prefix" to "przedrostek",
        "suffix" to "przyrostek",
        "infix" to "wrostek",
        "postp" to "poimek",
        "det" to "określnik",
        "article" to "przedimek",
        "particle" to "partykuła",
        "contraction" to "forma ściągnięta",
        "abbrev" to "skrót",
        "character" to "znak",
        "punct" to "interpunkcja",

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

    // Japanese counterparts, keyed by the same codes: the terms a 国語辞典
    // uses (五段動詞, サ変動詞, 連体詞, 尊敬語…). A card whose meaning came from
    // a monolingual dictionary shows these, not English or Polish ones.
    private val POS_LABELS_JA = mapOf(
        "v" to "動詞", "adj" to "形容詞", "prep" to "前置詞", "pron" to "代名詞",
        "intj" to "感動詞", "name" to "固有名詞", "phrase" to "句", "prefix" to "接頭辞",
        "suffix" to "接尾辞", "infix" to "接中辞", "postp" to "後置詞", "det" to "限定詞",
        "article" to "冠詞", "particle" to "助詞", "contraction" to "縮約形",
        "abbrev" to "略語", "character" to "文字", "punct" to "句読点",
        "v1" to "一段動詞", "v1-s" to "一段動詞（特殊）",
        "v5u" to "五段動詞（う）", "v5k" to "五段動詞（く）", "v5g" to "五段動詞（ぐ）",
        "v5s" to "五段動詞（す）", "v5t" to "五段動詞（つ）", "v5n" to "五段動詞（ぬ）",
        "v5b" to "五段動詞（ぶ）", "v5m" to "五段動詞（む）", "v5r" to "五段動詞（る）",
        "v5r-i" to "五段動詞（る・不規則）", "v5aru" to "五段動詞（〜ある）",
        "v5k-s" to "五段動詞（行く）", "v5u-s" to "五段動詞（う・特殊）", "v5uru" to "五段動詞（〜うる）",
        "v2a-s" to "二段動詞（特殊）", "vk" to "カ変動詞", "vs" to "サ変動詞",
        "vs-i" to "サ変動詞", "vs-s" to "サ変動詞（特殊）", "vs-c" to "サ変動詞（〜す）",
        "vt" to "他動詞", "vi" to "自動詞", "vn" to "ナ変動詞", "vr" to "ラ変動詞",
        "vz" to "ザ変動詞", "aux" to "助動詞", "aux-v" to "助動詞", "aux-adj" to "補助形容詞",
        "adj-i" to "形容詞", "adj-ix" to "形容詞（よい・いい）", "adj-na" to "形容動詞",
        "adj-no" to "の形容詞", "adj-pn" to "連体詞", "adj-t" to "形容動詞（タリ）",
        "adj-f" to "連体修飾語", "adj-ku" to "ク活用形容詞（古語）",
        "adj-shiku" to "シク活用形容詞（古語）", "adj-nari" to "ナリ活用形容動詞（古語）",
        "n" to "名詞", "n-suf" to "接尾名詞", "n-pref" to "接頭名詞", "n-t" to "時の名詞",
        "n-adv" to "副詞的名詞", "pn" to "代名詞", "num" to "数詞",
        "adv" to "副詞", "adv-to" to "副詞（と）", "prt" to "助詞", "conj" to "接続詞",
        "int" to "感動詞", "exp" to "表現", "pref" to "接頭辞", "suf" to "接尾辞",
        "ctr" to "助数詞", "cop" to "断定の助動詞",
        "uk" to "通常かな表記", "obs" to "廃語", "arch" to "古語", "rare" to "まれ",
        "sl" to "俗語", "col" to "口語", "fam" to "くだけた表現", "hon" to "尊敬語",
        "hum" to "謙譲語", "pol" to "丁寧語", "vulg" to "卑語", "derog" to "軽蔑語",
        "joc" to "おどけた表現", "abbr" to "略語", "yoji" to "四字熟語", "id" to "慣用句",
        "proverb" to "ことわざ", "quote" to "引用", "on-mim" to "擬音語・擬態語",
        "comp" to "情報", "math" to "数学", "med" to "医学", "law" to "法律",
        "Buddh" to "仏教", "Shinto" to "神道", "physics" to "物理", "chem" to "化学",
        "biol" to "生物", "bus" to "ビジネス", "econ" to "経済", "food" to "料理",
        "sports" to "スポーツ", "music" to "音楽"
    )

    private val USAGE_LABELS_JA = mapOf(
        "usually kana" to "通常かな表記", "usually kanji" to "通常漢字表記",
        "obsolete" to "廃語", "archaic" to "古語", "rare" to "まれ", "slang" to "俗語",
        "colloq." to "口語", "familiar" to "くだけた表現", "honorific" to "尊敬語",
        "humble" to "謙譲語", "polite" to "丁寧語", "vulgar" to "卑語",
        "euph." to "婉曲表現", "derog." to "軽蔑語",
        "Hokkaido dial." to "北海道方言", "Kansai dial." to "関西方言", "Kanto dial." to "関東方言",
        "Kyoto dial." to "京都方言", "Kyushu dial." to "九州方言", "Nagano dial." to "長野方言",
        "Osaka dial." to "大阪方言", "Ryukyu dial." to "琉球方言", "Tohoku dial." to "東北方言",
        "Tosa dial." to "土佐方言", "Tsugaru dial." to "津軽方言",
        "joc." to "おどけた表現", "abbr." to "略語", "yoji." to "四字熟語", "idiom" to "慣用句",
        "proverb" to "ことわざ", "quote" to "引用", "onomat." to "擬音語", "mimetic" to "擬態語",
        "formal" to "改まった表現", "male" to "男性語", "fem." to "女性語", "children" to "幼児語",
        "X-rated" to "成人向け", "comp." to "情報", "math" to "数学", "med." to "医学",
        "law" to "法律", "Buddh." to "仏教", "Shinto" to "神道", "phys." to "物理",
        "chem." to "化学", "biol." to "生物", "bus." to "ビジネス", "econ." to "経済",
        "food" to "料理", "sports" to "スポーツ", "music" to "音楽"
    )

    // Visual / metadata markers that show up in Yomitan termTags but are not
    // real grammar info. "P" = priority form. Filtering them keeps the POS
    // chip readable. The badge glyphs and corpus bands live in [isBadgeTag],
    // which the scanner's filters share.
    private val NON_POS_NOISE = setOf(
        "P", "priority", "form",
        // kaikki's placeholder when it could not determine a part of speech.
        // Roughly 3% of kty-en-pl entries carry it, and "unknown" printed
        // above a gloss tells the reader nothing.
        "unknown"
    )

    /**
     * JMdict's corpus-priority badges, as one definition.
     *
     * A plain JMdict import hands the parser `definitionTags` + `termTags`
     * verbatim — "1 prt, ⭐ spec", "2 v5u vt, v5, ⭐ ichi news1k" — while a
     * Jitendex import drops them at parse time. That is why a star appeared on
     * SOME cards and not others: it depends on which dictionary supplied the
     * entry, not on the word.
     *
     * ⭐ is U+2B50, which has emoji presentation: it renders in the font's own
     * colour whatever the card CSS says, so it ignored the card's palette and
     * pulled the eye off the word. The formatter dropped ★ (U+2605) but not
     * the one JMdict actually ships.
     *
     * There were two copies of this rule — this one, and the scanner's, which
     * was the complete one. They are one rule now, because a token either is a
     * part of speech or is not, and both callers are asking that.
     */
    fun isBadgeTag(token: String): Boolean {
        val tag = token.trim().lowercase()
        if (tag.isEmpty()) return false
        return tag.all { it.isDigit() } ||
            tag == "⭐" || tag == "★" || tag == "forms" ||
            tag in PRIORITY_TAGS ||
            NEWS_RANK.matches(tag) ||
            NF_RANK.matches(tag)
    }

    private val PRIORITY_TAGS = setOf(
        "ichi", "ichi1", "ichi2", "news", "spec", "spec1", "spec2", "gai", "gai1", "gai2"
    )

    /** JMdict's corpus bands: news1k … news25k, nf01 … nf48. */
    private val NEWS_RANK = Regex("news\\d+k?")
    private val NF_RANK = Regex("nf\\d+")

    private val JLPT_REGEX = Regex("""(?i)^jlpt[\s_-]?n?[\s_-]?[1-5]$""")
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
    fun format(rawTags: String, english: Boolean = true): String = format(rawTags, LabelLanguage.of(english))

    /**
     * [format] in [language]. A code the language's map lacks falls back to
     * the English label and then to the code itself, so nothing is dropped.
     */
    fun format(rawTags: String, language: LabelLanguage): String {
        if (rawTags.isBlank()) return ""
        val seen = LinkedHashSet<String>()
        for (token in SPLIT_REGEX.split(rawTags)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed in NON_POS_NOISE) continue
            if (isBadgeTag(trimmed)) continue
            if (JLPT_REGEX.matches(trimmed)) continue
            val label = when (language) {
                LabelLanguage.ENGLISH -> POS_LABELS[trimmed]
                LabelLanguage.POLISH -> POS_LABELS_PL[trimmed] ?: POS_LABELS[trimmed]
                LabelLanguage.JAPANESE -> POS_LABELS_JA[trimmed] ?: POS_LABELS[trimmed]
            }
            seen.add(label ?: trimmed)
        }
        return seen.joinToString(if (language == LabelLanguage.JAPANESE) "、" else ", ")
    }

    /**
     * Translate a single already-normalised usage-tag label (the English short
     * form stored in [com.yomitanmobile.domain.model.WordEntry.usageTags]) to
     * Polish when [english] is false. Unknown labels pass through unchanged.
     */
    fun localizeUsageTag(label: String, english: Boolean): String = localizeUsageTag(label, LabelLanguage.of(english))

    fun localizeUsageTag(label: String, language: LabelLanguage): String = when (language) {
        LabelLanguage.ENGLISH -> label
        LabelLanguage.POLISH -> USAGE_LABELS_PL[label.trim()] ?: label
        LabelLanguage.JAPANESE -> USAGE_LABELS_JA[label.trim()] ?: label
    }
}
